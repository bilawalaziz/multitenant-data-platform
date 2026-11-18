package com.platform.ingestion.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

public final class TenantDtos {

    private TenantDtos() {}

    public record CreateTenantRequest(
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{2,30}$", message = "must match ^[a-z][a-z0-9-]{2,30}$")
                    String tenantId,
            @NotBlank @Size(max = 120) String displayName) {}

    public record TenantResponse(
            String tenantId,
            String displayName,
            String status,
            String schemaName,
            String kafkaTopic,
            Instant createdAt) {

        static TenantResponse from(Tenant t) {
            return new TenantResponse(
                    t.getTenantCode(),
                    t.getDisplayName(),
                    t.getStatus().name(),
                    t.getSchemaName(),
                    t.getKafkaTopic(),
                    t.getCreatedAt());
        }
    }

    public record SettingsRequest(
            @NotNull @Size(min = 1, max = 20) Map<String, @NotBlank @Size(max = 500) String> settings) {}

    public record SettingsResponse(String tenantId, String schemaName, Map<String, String> settings) {}
}
