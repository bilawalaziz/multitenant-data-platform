package com.platform.ingestion.retention;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retention windows per table. The idempotency window must not exceed the ingestion record window: idempotency
 * rows reference their ingestion record, and a reused key after the window is processed as a new request.
 */
@ConfigurationProperties(prefix = "platform.retention")
public record RetentionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1h") Duration fixedDelay,
        @DefaultValue("7d") Duration outboxPublished,
        @DefaultValue("7d") Duration idempotency,
        @DefaultValue("90d") Duration ingestionRecords,
        @DefaultValue("1000") int batchSize,
        @DefaultValue("100") int maxBatchesPerRun) {

    public RetentionProperties {
        requirePositive("fixed-delay", fixedDelay);
        requirePositive("outbox-published", outboxPublished);
        requirePositive("idempotency", idempotency);
        requirePositive("ingestion-records", ingestionRecords);
        if (batchSize < 1 || maxBatchesPerRun < 1) {
            throw new IllegalArgumentException(
                    "platform.retention.batch-size and max-batches-per-run must be at least 1");
        }
        if (idempotency.compareTo(ingestionRecords) > 0) {
            throw new IllegalArgumentException("platform.retention.idempotency (" + idempotency
                    + ") must not exceed platform.retention.ingestion-records (" + ingestionRecords + ")");
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("platform.retention." + name + " must be positive");
        }
    }
}
