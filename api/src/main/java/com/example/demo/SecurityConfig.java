package com.example.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                // This API authenticates only through bearer headers.
                // Revisit CSRF protection when adding cookie authentication.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/public").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/me")
                        .authenticated()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/orders",
                                "/api/orders/*")
                        .hasAuthority("ROLE_orders.read")
                        .requestMatchers(HttpMethod.POST, "/api/orders")
                        .hasAuthority("ROLE_orders.write")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                    .jwt(jwt -> jwt
                            .jwtAuthenticationConverter(// Converts a validated JWT into Spring Security's Authentication object
                                    customJwtAuthenticationConverter())))
                .build();
    }

    private JwtAuthenticationConverter customJwtAuthenticationConverter() {
        var scopeConverter = new JwtGrantedAuthoritiesConverter();
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>();

            // Preserve Spring's standard SCOPE_ authorities.
            var scopes = scopeConverter.convert(jwt);
            authorities.addAll(scopes);

            // Add Keycloak realm roles as ROLE_ authorities.
            authorities.addAll(realmRoles(jwt));
            return authorities;
        });

        return converter;
    }

    private Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        var realmAccess = jwt.getClaims().get("realm_access");
        if (realmAccess instanceof Map<?, ?> access) {
            Object roles = access.get("roles");

            if (roles instanceof Collection<?> roleNames) {
                for (Object role : roleNames) {
                    if (role instanceof String name) {
                        authorities.add(
                                new SimpleGrantedAuthority("ROLE_" + name));
                    }
                }
            }
        }

        return authorities;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(
                List.of("http://localhost:3000"));
        configuration.setAllowedMethods(
                List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "Idempotency-Key"
        ));

        configuration.setExposedHeaders(List.of(
                "Location",
                "Idempotency-Replayed"
        ));

        // API authentication uses bearer headers, not cookies.
        configuration.setAllowCredentials(false);

        configuration.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}
