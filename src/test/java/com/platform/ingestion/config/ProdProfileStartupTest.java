package com.platform.ingestion.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.ingestion.PlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** The prod profile has no fallback credentials: a missing variable stops startup before any connection. */
class ProdProfileStartupTest {

    @Test
    void startupFailsWhenDatabaseCredentialsAreMissing() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(PlatformApplication.class)
                        .web(WebApplicationType.NONE)
                        .profiles("prod")
                        .properties(
                                "DB_URL=jdbc:postgresql://127.0.0.1:1/none",
                                "KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:1",
                                "KEYCLOAK_ISSUER_URI=http://127.0.0.1:1/realms/x",
                                "KEYCLOAK_JWK_SET_URI=http://127.0.0.1:1/certs")
                        .run())
                .hasStackTraceContaining("Could not resolve placeholder 'DB_USER'");
    }
}
