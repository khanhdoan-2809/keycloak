import Keycloak from "keycloak-js";
import "./style.css";

const APP_URL = "http://localhost:3000/";
const API_BASE = "http://localhost:8081";

const keycloak = new Keycloak({
  url: "http://localhost:8080",
  realm: "demo",
  clientId: "demo-web"
});

const $ = (id) => document.getElementById(id);

let ready = false;
let busy = false;
let lastRefresh = "Not attempted";
let lastApiCheck = null;

const events = [];

function log(message) {
  events.unshift(`${new Date().toISOString()}  ${message}`);

  $("events").textContent = events
    .slice(0, 30)
    .join("\n");
}

function status(message) {
  $("status").textContent = message;
}

function format(value) {
  return JSON.stringify(value, null, 2);
}

function utc(seconds) {
  return Number.isFinite(seconds)
    ? new Date(seconds * 1000).toISOString()
    : null;
}

function render() {
  const present = Boolean(keycloak.token);
  const claims = keycloak.tokenParsed;

  // The adapter estimates browser/server clock difference at token issuance.
  const remaining = claims?.exp == null
    ? null
    : claims.exp
      - Math.ceil(Date.now() / 1000)
      + (keycloak.timeSkew ?? 0);

  $("login").hidden = present;
  $("login").disabled = !ready || busy;

  $("logout").hidden = !present;
  $("logout").disabled = busy;

  for (const id of [
    "identity",
    "orders",
    "raw-probe",
    "clear-local"
  ]) {
    $(id).disabled = !ready || !present || busy;
  }

  $("force-refresh").disabled =
    !ready || !keycloak.refreshToken || busy;

  $("create-fields").disabled =
    !ready || !present || busy;

  $("find-fields").disabled =
    !ready || !present || busy;

  $("refresh-before-api").disabled = busy;

  $("metadata").textContent = format({
    localTokensPresent: present,

    username:
      claims?.preferred_username ?? null,

    accessIssuedAt:
      utc(claims?.iat),

    accessExpiresAt:
      utc(claims?.exp),

    estimatedSecondsRemaining:
      remaining,

    refreshTokenReportedExpiresAt:
      utc(keycloak.refreshTokenParsed?.exp),

    tokenApiRoles:
      claims?.resource_access?.["demo-api"]?.roles ?? [],

    keycloakSessionStatus:
      "Not checked continuously; local tokens do not prove an active SSO session",

    lastRefresh,

    // Historical result, not a continuously updated authorization decision.
    lastApiCheck
  });
}

async function refresh(force) {
  lastRefresh = `Attempt at ${new Date().toISOString()}`;

  log(
    force
      ? "Forced refresh requested"
      : "Checking token freshness before API call"
  );

  try {
    // -1 forces a request. 30 refreshes only when expiry is near.
    const changed = await keycloak.updateToken(
      force ? -1 : 30
    );

    lastRefresh = changed
      ? `Succeeded at ${new Date().toISOString()}`
      : "Skipped: access token has sufficient remaining lifetime";

    log(
      changed
        ? "Refresh succeeded; replacement token received"
        : "No refresh needed"
    );

    if (changed) {
      lastApiCheck = null;
    }

    return true;
  } catch {
    lastRefresh = `Failed at ${new Date().toISOString()}`;

    log(
      "Refresh failed; inspect the token endpoint status in browser Network"
    );

    status(
      keycloak.token
        ? "Refresh failed. Local tokens remain; this may be a network failure. Use the raw probe to check the API."
        : "Refresh failed and local tokens were cleared. Sign in again."
    );

    return false;
  } finally {
    render();
  }
}

async function operation(action) {
  if (!ready || busy) {
    return;
  }

  busy = true;
  render();

  try {
    await action();
  } catch {
    status(
      "Operation failed. Check browser Network for details."
    );
  } finally {
    busy = false;
    render();
  }
}

async function callApi(
  path,
  {
    method = "GET",
    body,
    key,
    raw = false
  } = {}
) {
  if (!keycloak.token) {
    return;
  }

  const shouldRefresh =
    !raw && $("refresh-before-api").checked;

  if (shouldRefresh && !(await refresh(false))) {
    return;
  }

  if (!keycloak.token) {
    return;
  }

  const headers = {
    Accept: "application/json",
    Authorization: `Bearer ${keycloak.token}`
  };

  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
  }

  if (key) {
    headers["Idempotency-Key"] = key;
  }

  let response;
  let text;

  try {
    response = await fetch(`${API_BASE}${path}`, {
      method,
      headers,

      body:
        body === undefined
          ? undefined
          : JSON.stringify(body),

      credentials: "omit",
      redirect: "error",
      cache: "no-store",
      signal: AbortSignal.timeout(10000)
    });

    text = await response.text();
  } catch {
    status("API request failed. Check availability and CORS.");

    $("output").textContent =
      method === "POST"
        ? "Creation may have completed. Retry with the SAME key and payload."
        : "No API response was received.";

    log(
      `${method} API request failed without a complete response`
    );

    return;
  }

  let data;

  try {
    data = JSON.parse(text);
  } catch {
    data = null;
  }

  const replayed =
    response.headers.get("Idempotency-Replayed");

  $("output").textContent =
    `HTTP ${response.status}\n`
    + (
      replayed === null
        ? ""
        : `Idempotency-Replayed: ${replayed}\n`
    )
    + `\n${
      data === null
        ? text || "(empty response)"
        : format(data)
    }`;

  log(
    `${raw ? "Raw probe: " : ""}${method} ${path} → ${response.status}`
  );

  if (path === "/api/me") {
    lastApiCheck = {
      receivedAt: new Date().toISOString(),
      status: response.status,

      authorities:
        response.ok
          ? data?.authorities ?? []
          : null,

      serverTime:
        response.ok
          ? data?.serverTime ?? null
          : null,

      secondsUntilExpiry:
        response.ok
          ? data?.secondsUntilExpiry ?? null
          : null
    };
  }

  if (response.status === 401) {
    // Learning behavior: preserve the token instead of hiding the evidence.
    status(
      "API rejected this access token. It is preserved locally for inspection or refresh."
    );
  } else if (response.status === 403) {
    status(
      "Authenticated, but missing the required API permission."
    );
  } else if (response.status === 404) {
    status(
      "Order not found or not accessible to this identity."
    );
  } else if (response.status === 409) {
    status(
      "This key was used with different details. Use a new key for a new order."
    );
  } else if (!response.ok) {
    status(
      `API returned HTTP ${response.status}. See the response.`
    );
  } else if (method === "POST") {
    if (typeof data?.id === "string") {
      $("order-id").value = data.id;
    }

    status(
      replayed === "true"
        ? "Existing order returned; no duplicate created."
        : "Order created."
    );
  } else {
    status("API request succeeded.");
  }

  render();
}

$("login").addEventListener("click", () =>
  operation(async () => {
    log("Starting Authorization Code + PKCE login");

    await keycloak.login({
      redirectUri: APP_URL
    });
  })
);

$("logout").addEventListener("click", () =>
  operation(async () => {
    log(
      "Redirecting to Keycloak logout; this is not a per-request API revocation check"
    );

    await keycloak.logout({
      redirectUri: APP_URL
    });
  })
);

$("clear-local").addEventListener("click", () =>
  operation(async () => {
    await keycloak.clearToken();

    lastApiCheck = null;

    $("output").textContent =
      "Local tokens cleared. Keycloak SSO may still be active.";

    status(
      "Local tokens cleared only. Click Sign in to try the existing SSO session."
    );

    log(
      "Local clear requested; no provider logout request was sent"
    );
  })
);

$("force-refresh").addEventListener("click", () =>
  operation(async () => {
    if (await refresh(true)) {
      status(
        "Refresh completed. Probe /api/me to see server authorities."
      );
    }
  })
);

$("raw-probe").addEventListener("click", () =>
  operation(() =>
    callApi("/api/me", { raw: true })
  )
);

$("identity").addEventListener("click", () =>
  operation(() => callApi("/api/me"))
);

$("orders").addEventListener("click", () =>
  operation(() =>
    callApi("/api/orders?limit=20&offset=0")
  )
);

$("refresh-before-api").addEventListener("change", () => {
  log(
    $("refresh-before-api").checked
      ? "Refresh-before-API enabled; there is still no background refresh loop"
      : "Refresh-before-API disabled for lifecycle experiments"
  );
});

$("new-key").addEventListener("click", () => {
  $("request-key").value = crypto.randomUUID();
  status("New key generated for a new order.");
});

$("create-form").addEventListener("submit", (event) => {
  event.preventDefault();

  if (busy || !keycloak.token) {
    return;
  }

  $("product").value = $("product").value.trim();
  $("request-key").value = $("request-key").value.trim();

  if (!$("create-form").reportValidity()) {
    return;
  }

  const quantity = Number($("quantity").value);

  if (
    !Number.isInteger(quantity)
    || quantity < 1
    || quantity > 100
  ) {
    return;
  }

  operation(() =>
    callApi("/api/orders", {
      method: "POST",
      key: $("request-key").value,
      body: {
        product: $("product").value,
        quantity
      }
    })
  );
});

$("find-form").addEventListener("submit", (event) => {
  event.preventDefault();

  if (busy || !keycloak.token) {
    return;
  }

  $("order-id").value = $("order-id").value.trim();

  if (!$("find-form").reportValidity()) {
    return;
  }

  operation(() =>
    callApi(
      `/api/orders/${encodeURIComponent($("order-id").value)}`
    )
  );
});

keycloak.onAuthSuccess = () => {
  log("Login succeeded; tokens held in memory");
};

keycloak.onAuthRefreshSuccess = () => {
  log("Adapter reports refresh success");
};

keycloak.onAuthRefreshError = () => {
  log("Adapter reports refresh failure");
};

keycloak.onTokenExpired = () => {
  log(
    "Adapter reports access-token expiry; no automatic refresh is triggered here"
  );

  render();
};

keycloak.onAuthLogout = () => {
  lastApiCheck = null;

  log(
    "Adapter cleared local tokens; this event alone does not prove provider logout"
  );

  $("output").textContent = "No local tokens remain.";
  render();
};

async function start() {
  render();

  $("request-key").value = crypto.randomUUID();

  try {
    const authenticated = await keycloak.init({
      flow: "standard",
      pkceMethod: "S256",
      responseMode: "fragment",
      checkLoginIframe: false,
      redirectUri: APP_URL
    });

    ready = true;

    status(
      authenticated
        ? "Local login completed. Use the probes to inspect token behavior."
        : "No local tokens. Click Sign in."
    );

    log(
      authenticated
        ? "Login callback processed"
        : "Started without local tokens"
    );
  } catch {
    status(
      "Authentication initialization failed. Check Keycloak client settings and reload."
    );

    log("Initialization failed; inspect browser Network");
  }

  render();
}

// Display only: this sends no requests and performs no refresh.
setInterval(render, 1000);

start();