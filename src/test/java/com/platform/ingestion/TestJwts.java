package com.platform.ingestion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Builds JWTs shaped like Keycloak tokens for unit and integration tests. */
public final class TestJwts {

    public static final String ISSUER = "http://localhost:8180/realms/sensor-platform";
    public static final String AUDIENCE = "sensor-platform-api";

    private TestJwts() {}

    /** @param realmRoles Keycloak realm role names such as "tenant-admin"; tenantId may be null. */
    public static Jwt jwt(String subject, String tenantId, List<String> realmRoles) {
        return jwt(subject, tenantId, null, realmRoles);
    }

    /** Like {@link #jwt(String, String, List)} with a device_id claim when deviceId is not null. */
    public static Jwt jwt(String subject, String tenantId, String deviceId, List<String> realmRoles) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token-" + subject)
                .header("alg", "none")
                .subject(subject)
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("preferred_username", subject)
                .claim("realm_access", Map.of("roles", realmRoles));
        if (tenantId != null) {
            builder.claim("tenant_id", tenantId);
        }
        if (deviceId != null) {
            builder.claim("device_id", deviceId);
        }
        return builder.build();
    }

    /** Authentication with already-mapped ROLE_* authorities (what the converter produces). */
    public static JwtAuthenticationToken auth(String tenantId, String... springRoles) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String role : springRoles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        return new JwtAuthenticationToken(jwt("user", tenantId, List.of()), authorities, "user");
    }
}
