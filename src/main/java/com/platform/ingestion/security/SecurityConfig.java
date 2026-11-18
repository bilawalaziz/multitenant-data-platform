package com.platform.ingestion.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, RestAuthenticationEntryPoint entryPoint, RestAccessDeniedHandler accessDeniedHandler)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(o -> o.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(j -> j.jwtAuthenticationConverter(new KeycloakJwtAuthenticationConverter())))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                        .permitAll()
                        .requestMatchers("/actuator/**")
                        .denyAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**")
                        .permitAll()
                        .requestMatchers("/error")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/ingestion/**")
                        .hasRole(Roles.TENANT_INGEST)
                        .requestMatchers("/api/v1/admin/**")
                        .hasRole(Roles.PLATFORM_ADMIN)
                        .requestMatchers(
                                HttpMethod.GET, "/api/v1/tenants/*/ingestions", "/api/v1/tenants/*/ingestions/**")
                        .hasAnyRole(Roles.PLATFORM_ADMIN, Roles.TENANT_ADMIN, Roles.TENANT_INGEST)
                        .requestMatchers("/api/v1/tenants/**")
                        .hasAnyRole(Roles.PLATFORM_ADMIN, Roles.TENANT_ADMIN)
                        .anyRequest()
                        .authenticated());
        return http.build();
    }

    /**
     * Keys come from the (possibly internal) JWKS endpoint; the issuer is validated against the public issuer
     * URI, and the audience must include the API audience.
     */
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${platform.security.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri), new AudienceValidator(audience));
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
