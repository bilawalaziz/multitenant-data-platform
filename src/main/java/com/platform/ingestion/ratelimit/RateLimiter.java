package com.platform.ingestion.ratelimit;

import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.common.Hashing;
import com.platform.ingestion.observability.PlatformMetrics;
import com.platform.ingestion.ratelimit.RateLimitProperties.Limit;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Per-tenant and per-device token buckets shared through Redis. Tenant and device ids appear only in Redis keys,
 * never in metrics. When Redis fails the check is skipped (fail open): losing rate limiting is better than
 * rejecting all ingestion, and {@code ratelimit.errors} makes the degradation visible.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final long WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final RateLimitProperties properties;
    private final RedisProxyManagerSource proxyManagers;
    private final PlatformMetrics metrics;

    private final AtomicLong lastWarnNanos = new AtomicLong(System.nanoTime() - WARN_INTERVAL_NANOS);
    private final AtomicLong suppressedWarnings = new AtomicLong();
    private final AtomicLong skipUntilNanos = new AtomicLong();

    public RateLimiter(RateLimitProperties properties, RedisProxyManagerSource proxyManagers, PlatformMetrics metrics) {
        this.properties = properties;
        this.proxyManagers = proxyManagers;
        this.metrics = metrics;
    }

    /** Keyed by tenant code, so the check needs no database lookup. */
    public void checkTenant(String tenantCode) {
        consume("rl:t:" + tenantCode, properties.tenantLimit(tenantCode), "tenant");
    }

    public void checkDevice(String tenantCode, String deviceId) {
        // Hashed because the id comes from a payload and could otherwise make arbitrarily large keys.
        consume("rl:d:" + tenantCode + ":" + Hashing.sha256Hex(deviceId), properties.device(), "device");
    }

    private void consume(String key, Limit limit, String scope) {
        if (!properties.enabled() || !storeAvailable()) {
            return;
        }
        ConsumptionProbe probe;
        try {
            ProxyManager<String> manager = proxyManagers.get();
            if (manager == null) {
                // Another thread is connecting; do not queue behind it.
                metrics.rateLimitSkipped();
                return;
            }
            // The configuration is only used when the bucket does not exist yet, so a changed limit applies
            // once the old bucket has expired.
            probe = manager.builder().build(key, () -> configuration(limit)).tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException e) {
            onStoreFailure(e);
            return;
        }
        // A success while probing after a failure closes the breaker again.
        skipUntilNanos.set(0);
        if (!probe.isConsumed()) {
            long seconds = retryAfterSeconds(probe.getNanosToWaitForRefill());
            throw new ApiException(
                    ErrorCode.RATE_LIMITED,
                    "Rate limit exceeded for this " + scope + "; retry after " + seconds + " s",
                    null,
                    null,
                    Map.of("Retry-After", Long.toString(seconds)));
        }
    }

    /**
     * Lock-free circuit breaker. After a failure every check is skipped until the cooldown ends; then one caller
     * wins the right to probe Redis while the others keep skipping, so a dead Redis costs one timeout per
     * cooldown instead of one per request.
     */
    private boolean storeAvailable() {
        long until = skipUntilNanos.get();
        if (until == 0) {
            return true;
        }
        long now = System.nanoTime();
        if (now - until < 0) {
            metrics.rateLimitSkipped();
            return false;
        }
        if (skipUntilNanos.compareAndSet(until, now + cooldownNanos())) {
            return true;
        }
        metrics.rateLimitSkipped();
        return false;
    }

    private long cooldownNanos() {
        return properties.failureCooldown().toNanos();
    }

    static BucketConfiguration configuration(Limit limit) {
        // One token per period supports sustained rates below 1 per second.
        Duration period = Duration.ofNanos(Math.max(1, Math.round(1_000_000_000d / limit.refillPerSecond())));
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limit.capacity())
                        .refillGreedy(1, period)
                        .build())
                .build();
    }

    /** Whole seconds, rounded up and at least 1: a client that waits this long is guaranteed a token. */
    static long retryAfterSeconds(long nanosToWait) {
        long seconds = (Math.max(nanosToWait, 0) + 999_999_999L) / 1_000_000_000L;
        return Math.max(1, seconds);
    }

    private void onStoreFailure(RuntimeException e) {
        metrics.rateLimitError();
        long now = System.nanoTime();
        // 0 means closed, so never store it as a deadline.
        skipUntilNanos.set(Math.max(1, now + cooldownNanos()));
        long last = lastWarnNanos.get();
        if (now - last >= WARN_INTERVAL_NANOS && lastWarnNanos.compareAndSet(last, now)) {
            log.warn(
                    "Rate limiter store unavailable, admitting requests without limits ({} further errors since the"
                            + " last warning): {}",
                    suppressedWarnings.getAndSet(0),
                    e.toString());
        } else {
            suppressedWarnings.incrementAndGet();
        }
    }
}
