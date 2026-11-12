package com.example.demo;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

@Component
public class ApiAuthoritiesConverter
        implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String API_CLIENT_ID = "demo-api";

    private final JwtGrantedAuthoritiesConverter scopeConverter =
            new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        var authorities = new LinkedHashSet<GrantedAuthority>();

        // Preserve Spring's standard SCOPE_ authorities.
        var scopeAuthorities = scopeConverter.convert(jwt);

        if (scopeAuthorities != null) {
            authorities.addAll(scopeAuthorities);
        }

        Object resourceAccess =
                jwt.getClaims().get("resource_access");

        if (!(resourceAccess instanceof Map<?, ?> clients)) {
            return authorities;
        }

        Object apiAccess = clients.get(API_CLIENT_ID);

        if (!(apiAccess instanceof Map<?, ?> apiClaims)) {
            return authorities;
        }

        Object rolesValue = apiClaims.get("roles");

        if (!(rolesValue instanceof Collection<?> roles)) {
            return authorities;
        }

        // Fail closed for a malformed API role collection.
        boolean malformed = roles.stream().anyMatch(role ->
                !(role instanceof String)
                        || ((String) role).isBlank());

        if (malformed) {
            return authorities;
        }

        for (Object role : roles) {
            authorities.add(new SimpleGrantedAuthority(
                    API_CLIENT_ID + ":" + (String) role));
        }

        return authorities;
    }
}