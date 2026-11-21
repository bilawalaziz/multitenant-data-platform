package com.platform.ingestion.credential;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param url base URL of Keycloak as reachable from the application (no realm path)
 * @param deviceScope shared client scope (defined in the realm) that puts tenant, device and the API audience
 * into a device client's tokens
 * @param deviceTokenLifespan access-token lifetime set on every device client, in whole seconds; shorter than the
 * realm default because a device re-authenticates cheaply and a leaked token should expire quickly
 */
@ConfigurationProperties(prefix = "platform.keycloak.provisioner")
public record KeycloakProvisionerProperties(
        @DefaultValue("http://localhost:8180") String url,
        @DefaultValue("sensor-platform") String realm,
        @DefaultValue("sensor-platform-provisioner") String clientId,
        @DefaultValue("") String clientSecret,
        @DefaultValue("device-identity") String deviceScope,
        @DefaultValue("tenant-ingest") String ingestRole,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout,
        @DefaultValue("300s") Duration deviceTokenLifespan) {

    public KeycloakProvisionerProperties {
        if (deviceTokenLifespan == null || deviceTokenLifespan.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException(
                    "platform.keycloak.provisioner.device-token-lifespan must be at least 1s");
        }
    }

    String adminBase() {
        return trimmedUrl() + "/admin/realms/" + realm;
    }

    String tokenUri() {
        return trimmedUrl() + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    String deviceTokenLifespanSeconds() {
        return Long.toString(deviceTokenLifespan.toSeconds());
    }

    private String trimmedUrl() {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
