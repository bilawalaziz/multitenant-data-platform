package com.platform.ingestion.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Custom metrics. Labels are low cardinality only (outcome, reason, result): never tenant or device ids. */
@Component
public class PlatformMetrics {

    private final MeterRegistry registry;
    private final Counter accepted;
    private final Counter duplicate;
    private final Counter rejected;
    private final Counter validationFailures;
    private final Counter rateLimitErrors;
    private final Counter rateLimitSkipped;
    private final Counter publishSuccess;
    private final Counter publishFailure;
    private final Map<String, Counter> retentionDeleted;
    private final Timer processing;
    private final AtomicLong pendingEvents = new AtomicLong();
    private final AtomicLong failedEvents = new AtomicLong();

    public PlatformMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.accepted = Counter.builder("ingestion.requests")
                .tag("outcome", "accepted")
                .description("Ingestion requests by outcome")
                .register(registry);
        this.duplicate = Counter.builder("ingestion.requests")
                .tag("outcome", "duplicate")
                .description("Ingestion requests by outcome")
                .register(registry);
        this.rejected = Counter.builder("ingestion.requests")
                .tag("outcome", "rejected")
                .description("Ingestion requests by outcome")
                .register(registry);
        this.validationFailures = Counter.builder("ingestion.validation.failures")
                .description("Payloads that failed contract validation")
                .register(registry);
        this.rateLimitErrors = Counter.builder("ratelimit.errors")
                .description("Rate limit checks skipped because the limiter store failed (requests fail open)")
                .register(registry);
        this.rateLimitSkipped = Counter.builder("ratelimit.skipped")
                .description("Rate limit checks skipped while the limiter store is in its failure cooldown")
                .register(registry);
        this.publishSuccess = Counter.builder("outbox.publish")
                .tag("result", "success")
                .description("Outbox publish attempts")
                .register(registry);
        this.publishFailure = Counter.builder("outbox.publish")
                .tag("result", "failure")
                .description("Outbox publish attempts")
                .register(registry);
        this.retentionDeleted = List.of("outbox_events", "idempotency_records", "ingestion_records").stream()
                .collect(Collectors.toMap(table -> table, table -> Counter.builder("retention.deleted")
                        .tag("table", table)
                        .description("Rows purged by the retention job")
                        .register(registry)));
        this.processing = Timer.builder("ingestion.processing")
                .description("End-to-end ingestion processing time")
                .publishPercentileHistogram()
                .register(registry);
        Gauge.builder("outbox.pending.events", pendingEvents, AtomicLong::get)
                .description("Outbox rows waiting to be published")
                .register(registry);
        Gauge.builder("outbox.failed.events", failedEvents, AtomicLong::get)
                .description("Outbox rows that exhausted their attempts")
                .register(registry);
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void stopTimer(Timer.Sample sample) {
        sample.stop(processing);
    }

    public void requestAccepted() {
        accepted.increment();
    }

    public void requestDuplicate() {
        duplicate.increment();
    }

    public void requestRejected(String reason) {
        rejected.increment();
        Counter.builder("ingestion.rejections")
                .tag("reason", reason)
                .description("Rejected ingestion requests by error code")
                .register(registry)
                .increment();
    }

    public void rateLimitError() {
        rateLimitErrors.increment();
    }

    public void rateLimitSkipped() {
        rateLimitSkipped.increment();
    }

    public void validationFailed() {
        validationFailures.increment();
    }

    public void outboxPublishSucceeded() {
        publishSuccess.increment();
    }

    public void outboxPublishFailed() {
        publishFailure.increment();
    }

    public void retentionDeleted(String table, int count) {
        retentionDeleted.get(table).increment(count);
    }

    public void updateOutboxGauges(long pending, long failed) {
        pendingEvents.set(pending);
        failedEvents.set(failed);
    }
}
