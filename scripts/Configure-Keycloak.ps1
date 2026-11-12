param(
    [ValidateSet('Plan', 'Apply', 'Verify')]
    [string]$Mode = 'Plan',

    [string]$Server = 'http://localhost:8080',

    [string]$AdminRealm = 'master',

    [string]$ConfigPath = (
        Join-Path $PSScriptRoot '../keycloak/config/lifecycle.local.json'
    )
)

$ErrorActionPreference = 'Stop'

$settings = Get-Content -LiteralPath $ConfigPath -Raw |
    ConvertFrom-Json

# Prevent accidentally targeting the administrative realm.
if ($settings.realm -ne 'demo') {
    throw 'This local tutorial script is restricted to the demo realm.'
}

$Server = $Server.TrimEnd('/')

$realmPath =
    '/admin/realms/' +
    [Uri]::EscapeDataString($settings.realm)

function Invoke-Kc {
    param(
        [string]$Method,
        [string]$Path,
        $Body = $null
    )

    $request = @{
        Uri         = $Server + $Path
        Method      = $Method
        Headers     = $headers
        ErrorAction = 'Stop'
    }

    if ($null -ne $Body) {
        $request.ContentType = 'application/json'

        $request.Body = ConvertTo-Json `
            -InputObject $Body `
            -Depth 30 `
            -Compress
    }

    Invoke-RestMethod @request
}

function Same-Value {
    param($Left, $Right)

    $a = ConvertTo-Json `
        -InputObject $Left `
        -Depth 20 `
        -Compress

    $b = ConvertTo-Json `
        -InputObject $Right `
        -Depth 20 `
        -Compress

    return $a -ceq $b
}

function Get-Plan {
    $changes = @()
    $operations = @()

    # Read the current realm.
    $realm = Invoke-Kc 'Get' $realmPath

    # Include only managed fields that differ.
    $realmUpdate = @{}

    foreach (
        $property in $settings.realmSettings.PSObject.Properties
    ) {
        $before = $realm.($property.Name)

        if (-not (Same-Value $before $property.Value)) {
            $realmUpdate[$property.Name] = $property.Value

            $changes += [pscustomobject]@{
                Path   = 'realm.' + $property.Name
                Before = $before
                After  = $property.Value
            }
        }
    }

    if ($realmUpdate.Count -gt 0) {
        $operations += [pscustomobject]@{
            Path = $realmPath
            Body = $realmUpdate
        }
    }

    # Resolve and inspect every requested client before applying anything.
    foreach ($clientName in $settings.clients) {
        $query = [Uri]::EscapeDataString($clientName)

        $clientMatches = @(
            Invoke-Kc 'Get' "$realmPath/clients?clientId=$query"
        )

        $clientMatches = @(
            $clientMatches |
                Where-Object {
                    $_.clientId -ceq $clientName
                }
        )

        if ($clientMatches.Count -ne 1) {
            throw (
                "Expected one existing client '$clientName'; " +
                "found $($clientMatches.Count). " +
                "No updates have been sent by this planning pass."
            )
        }

        $clientPath =
            "$realmPath/clients/$($clientMatches[0].id)"

        $client = Invoke-Kc 'Get' $clientPath

        # Preserve attributes outside this configuration.
        $attributes = @{}

        if ($null -ne $client.attributes) {
            foreach (
                $property in $client.attributes.PSObject.Properties
            ) {
                $attributes[$property.Name] = $property.Value
            }
        }

        $changed = $false

        foreach (
            $property in $settings.clientAttributes.PSObject.Properties
        ) {
            $before = $attributes[$property.Name]

            if (-not (Same-Value $before $property.Value)) {
                $changes += [pscustomobject]@{
                    Path = (
                        "clients.$clientName.attributes." +
                        $property.Name
                    )
                    Before = $before
                    After  = $property.Value
                }

                $attributes[$property.Name] = $property.Value
                $changed = $true
            }
        }

        if ($changed) {
            $operations += [pscustomobject]@{
                Path = $clientPath
                Body = @{
                    attributes = $attributes
                }
            }
        }
    }

    return [pscustomobject]@{
        Changes    = $changes
        Operations = $operations
    }
}

# Credentials are prompted, not stored in source or command history.
$credential = Get-Credential `
    -UserName 'admin' `
    -Message 'Keycloak administrator credentials'

if ($null -eq $credential) {
    throw 'Authentication cancelled.'
}

$authRealm = [Uri]::EscapeDataString($AdminRealm)

$loginBody = @{
    grant_type = 'password'
    client_id  = 'admin-cli'
    username   = $credential.UserName
    password   = $credential.GetNetworkCredential().Password
}

try {
    $session = Invoke-RestMethod `
        -Method Post `
        -Uri "$Server/realms/$authRealm/protocol/openid-connect/token" `
        -ContentType 'application/x-www-form-urlencoded' `
        -Body $loginBody
}
finally {
    $loginBody.password = $null
    $credential = $null
}

$headers = @{
    Authorization = 'Bearer ' + $session.access_token
}

$session = $null

try {
    $plan = Get-Plan

    foreach ($change in $plan.Changes) {
        $before = ConvertTo-Json `
            -InputObject $change.Before `
            -Compress

        $after = ConvertTo-Json `
            -InputObject $change.After `
            -Compress

        Write-Host "$($change.Path): $before -> $after"
    }

    if ($Mode -eq 'Plan') {
        Write-Host (
            "Plan only: $($plan.Changes.Count) differences. " +
            "No changes applied."
        )

        return
    }

    if ($Mode -eq 'Apply') {
        foreach ($operation in $plan.Operations) {
            $null = Invoke-Kc `
                'Put' `
                $operation.Path `
                $operation.Body
        }

        # Read Keycloak again instead of assuming writes succeeded.
        $plan = Get-Plan
    }

    if ($plan.Changes.Count -gt 0) {
        throw (
            "Verification failed: " +
            "$($plan.Changes.Count) managed settings differ."
        )
    }

    Write-Host (
        'Verified: all managed lifecycle settings match the JSON file.'
    )
}
finally {
    $headers.Clear()
}