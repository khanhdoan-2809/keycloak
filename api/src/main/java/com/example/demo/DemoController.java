package com.example.demo;

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

        return Map.of(
                "subject", jwt.getSubject(),
                "issuer", jwt.getIssuer().toString(),
                "audiences", jwt.getAudience(),
                "authorities", authentication.getAuthorities()
                        .stream()
                        .map(GrantedAuthority::getAuthority)
                        .sorted()
                        .toList()
        );
    }
}