package com.example.demo;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoController {

    @GetMapping("/public")
    public Map<String, String> publicEndpoint() {
        return Map.of("message", "Anyone can access this endpoint");
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication) {

        return Map.of(
                "subject", jwt.getSubject(),
                "issuer", jwt.getIssuer().toString(),
                "audiences", jwt.getAudience(),
                "authorities", authentication.getAuthorities()
                        .stream()
                        .map(GrantedAuthority::getAuthority)
                        .toList()
        );
    }

    @GetMapping("/api/orders")
    public List<Order> orders() {
        return List.of(
                new Order(1, "Mechanical keyboard"),
                new Order(2, "USB-C dock")
        );
    }

    @PostMapping("/api/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> createOrder() {
        // Authorization demonstration only; no persistence yet.
        return Map.of("message", "Write permission accepted");
    }

    public record Order(long id, String product) {
    }
}