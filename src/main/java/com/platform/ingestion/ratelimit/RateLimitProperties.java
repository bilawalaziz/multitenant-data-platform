package com.platform.ingestion.ratelimit;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Token-bucket limits for ingestion. Defaults live in application.yml so that tenant and device can differ.
 *
 * @param failureCooldown after a Redis failure, limiting is skipped (requests are admitted) for this long
 * @param connectTimeout bound for establishing the Redis connection (DNS, TCP, auth); commands use the shorter
 * spring.data.redis.timeout. Connecting happens off the request path, so this can be generous.
 * @param tenantOverrides per tenant code; an unset field falls back to the tenant default
 */
@ConfigurationProperties(prefix = "platform.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2s") Duration failureCooldown,
        @DefaultValue("2s") Duration connectTimeout,
        Limit tenant,
        Limit device,
        Map<String, TenantOverride> tenantOverrides) {

    public RateLimitProperties {
        if (failureCooldown.isNegative() || failureCooldown.isZero()) {
            throw new IllegalArgumentException("platform.rate-limit.failure-cooldown must be positive");
        }
        if (connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("platform.rate-limit.connect-timeout must be positive");
        }
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
