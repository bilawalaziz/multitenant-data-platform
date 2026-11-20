package com.platform.ingestion.ratelimit;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Token-bucket limits for ingestion. Defaults live in application.yml so that tenant and device can differ.
 *
 * @param tenantOverrides per tenant code; an unset field falls back to the tenant default
 */
@ConfigurationProperties(prefix = "platform.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        Limit tenant,
        Limit device,
        Map<String, TenantOverride> tenantOverrides) {

    public RateLimitProperties {
        tenantOverrides = tenantOverrides == null ? Map.of() : Map.copyOf(tenantOverrides);
    }

    /**
     * @param capacity burst size in requests
     * @param refillPerSecond sustained rate; may be below 1
     */
    public record Limit(long capacity, double refillPerSecond) {

        public Limit {
            if (capacity < 1) {
                throw new IllegalArgumentException("platform.rate-limit capacity must be at least 1");
            }
            if (!(refillPerSecond > 0) || Double.isInfinite(refillPerSecond)) {
                throw new IllegalArgumentException("platform.rate-limit refill-per-second must be positive");
            }
        }
    }

    public record TenantOverride(Long capacity, Double refillPerSecond) {}

    public Limit tenantLimit(String tenantCode) {
        TenantOverride override = tenantOverrides.get(tenantCode);
        if (override == null) {
            return tenant;
        }
        return new Limit(
                override.capacity() != null ? override.capacity() : tenant.capacity(),
                override.refillPerSecond() != null ? override.refillPerSecond() : tenant.refillPerSecond());
    }
}
