package com.example.demo;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.support.BasicAuthenticationInterceptor;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;
import org.springframework.security.oauth2.server.resource.introspection.SpringOpaqueTokenIntrospector;
import org.springframework.util.Assert;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(OAuth2ResourceServerProperties.class)
public class TokenValidationConfig {

    @Bean
    JwtDecoder jwtDecoder(
            OAuth2ResourceServerProperties properties,
            @Value("${app.security.validation-mode:local}") String mode,
            @Value("${app.security.introspection-uri:}") String introspectionUri,
            @Value("${app.security.introspection-client-id:demo-api}") String clientId,
            @Value("${app.security.introspection-client-secret:}") String clientSecret) {

        if (!List.of("local", "introspection").contains(mode)) {
            throw new IllegalArgumentException("validation-mode must be local or introspection");
        }
        var settings = properties.getJwt();
        Assert.hasText(settings.getIssuerUri(), "JWT issuer-uri is required");
        Assert.hasText(settings.getJwkSetUri(), "JWT jwk-set-uri is required");
        Assert.notEmpty(settings.getAudiences(), "JWT audiences are required");

        var local = NimbusJwtDecoder.withJwkSetUri(settings.getJwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        List<String> audiences = List.copyOf(settings.getAudiences());
        OAuth2TokenValidator<Jwt> audienceValidator = jwt ->
                jwt.getAudience().stream().anyMatch(audiences::contains)
                        ? OAuth2TokenValidatorResult.success()
                        : invalid("Token is not intended for this API");

        OAuth2TokenValidator<Jwt> identityValidator = jwt ->
                jwt.getExpiresAt() != null
                        && jwt.getSubject() != null
                        && !jwt.getSubject().isBlank()
                        ? OAuth2TokenValidatorResult.success()
                        : invalid("Token must contain exp and sub");

        local.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(settings.getIssuerUri()),
                audienceValidator,
                identityValidator));

        if (mode.equals("local")) {
            return local;
        }

        Assert.hasText(introspectionUri, "Introspection URI is required");
        Assert.hasText(clientId, "Introspection client ID is required");
        Assert.hasText(clientSecret, "Introspection secret is required in introspection mode");

        var http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));
        var rest = new RestTemplate(factory);
        rest.getInterceptors().add(new BasicAuthenticationInterceptor(
                URLEncoder.encode(clientId, StandardCharsets.UTF_8),
                URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)));

        var introspector = new SpringOpaqueTokenIntrospector(introspectionUri, rest);

        return encoded -> {
            Jwt jwt = local.decode(encoded);
            try {
                // No result cache. Each authenticated request checks activity.
                introspector.introspect(encoded);
            } catch (BadOpaqueTokenException inactive) {
                throw new BadJwtException("Token is inactive");
            } catch (OAuth2IntrospectionException unavailable) {
                // Fail closed: never fall back to local-only acceptance.
                throw new AuthenticationServiceException(
                        "Token activity verification is unavailable", unavailable);
            }
            // Retain the verified JWT and existing client-role mapping.
            return jwt;
        };
    }

    private static OAuth2TokenValidatorResult invalid(String description) {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", description, null));
    }
}
