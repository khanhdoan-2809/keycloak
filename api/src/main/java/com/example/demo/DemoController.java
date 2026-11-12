package com.example.demo;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoController {

    private final CurrentIdentity identity;

    public DemoController(CurrentIdentity identity) {
        this.identity = identity;
    }

    @GetMapping("/public")
    public Map<String, String> publicEndpoint() {
        return Map.of(
                "message",
                "Anyone can access this endpoint");
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(
            Authentication authentication) {

        var jwt = identity.requireJwt();

        Instant now = Instant.now();
        Instant expiresAt = jwt.getExpiresAt();

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("subject", jwt.getSubject());
        result.put("issuer", jwt.getIssuer().toString());
        result.put("audiences", jwt.getAudience());
        result.put("issuedAt", jwt.getIssuedAt());
        result.put("expiresAt", expiresAt);
        result.put("serverTime", now);

        result.put(
                "secondsUntilExpiry",
                expiresAt == null
                        ? null
                        : Duration.between(now, expiresAt)
                        .getSeconds());

        result.put(
                "authorities",
                authentication.getAuthorities()
                        .stream()
                        .map(GrantedAuthority::getAuthority)
                        .sorted()
                        .toList());

        return result;
    }
}