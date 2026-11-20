package com.platform.ingestion.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.ingestion.PlatformApplication;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** The prod profile has no fallback values: any missing variable stops startup before any connection. */
class ProdProfileStartupTest {

    private static final Map<String, String> REQUIRED = new LinkedHashMap<>();

    static {
        REQUIRED.put("DB_URL", "jdbc:postgresql://127.0.0.1:1/none");
        REQUIRED.put("DB_USER", "u");
        REQUIRED.put("DB_PASSWORD", "p");
        REQUIRED.put("DB_MIGRATION_USER", "owner");
        REQUIRED.put("DB_MIGRATION_PASSWORD", "owner-pw");
        REQUIRED.put("KAFKA_BOOTSTRAP_SERVERS", "127.0.0.1:1");
        REQUIRED.put("KEYCLOAK_ISSUER_URI", "http://127.0.0.1:1/realms/x");
        REQUIRED.put("KEYCLOAK_JWK_SET_URI", "http://127.0.0.1:1/certs");
        REQUIRED.put("KEYCLOAK_ADMIN_URL", "http://127.0.0.1:1");
        REQUIRED.put("PROVISIONER_CLIENT_ID", "sensor-platform-provisioner");
        REQUIRED.put("PROVISIONER_CLIENT_SECRET", "s");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "DB_URL",
                "DB_USER",
                "DB_PASSWORD",
                "DB_MIGRATION_USER",
                "DB_MIGRATION_PASSWORD",
                "KAFKA_BOOTSTRAP_SERVERS",
                "KEYCLOAK_ISSUER_URI",
                "KEYCLOAK_JWK_SET_URI",
                "KEYCLOAK_ADMIN_URL",
                "PROVISIONER_CLIENT_ID",
                "PROVISIONER_CLIENT_SECRET"
            })
    void startupFailsNamingTheMissingVariable(String missing) {
        String[] provided = REQUIRED.entrySet().stream()
                .filter(e -> !e.getKey().equals(missing))
                .map(e -> e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);

        assertThatThrownBy(() -> new SpringApplicationBuilder(PlatformApplication.class)
                        .web(WebApplicationType.NONE)
                        .profiles("prod")
                        .properties(provided)
                        .run())
                .hasStackTraceContaining("Could not resolve placeholder '" + missing + "'");
    }
}
