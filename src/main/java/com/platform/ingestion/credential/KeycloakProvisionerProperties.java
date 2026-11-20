package com.platform.ingestion.credential;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param url base URL of Keycloak as reachable from the application (no realm path)
 * @param audience audience the device tokens must carry
 */
@ConfigurationProperties(prefix = "platform.keycloak.provisioner")
public record KeycloakProvisionerProperties(
        @DefaultValue("http://localhost:8180") String url,
        @DefaultValue("sensor-platform") String realm,
        @DefaultValue("sensor-platform-provisioner") String clientId,
        @DefaultValue("") String clientSecret,
        @DefaultValue("sensor-platform-api") String audience,
        @DefaultValue("tenant-ingest") String ingestRole,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout) {

    String adminBase() {
        return trimmedUrl() + "/admin/realms/" + realm;
    }

    String tokenUri() {
        return trimmedUrl() + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    private String trimmedUrl() {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
