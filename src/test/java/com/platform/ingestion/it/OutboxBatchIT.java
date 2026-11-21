package com.platform.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.ingestion.messaging.BackoffPolicy;
import com.platform.ingestion.messaging.OutboxProperties;
import com.platform.ingestion.messaging.OutboxPublisher;
import com.platform.ingestion.messaging.PlatformKafkaProperties;
import com.platform.ingestion.messaging.PublishException;
import com.platform.ingestion.observability.PlatformMetrics;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Batch publishing: sends are issued together, a failing topic is isolated, issuing stops at the deadline. */
class OutboxBatchIT extends AbstractIntegrationTest {

    private static final String TENANT_A = "tenant-batch-a";
    private static final String TENANT_B = "tenant-batch-b";

    @Autowired
    private PlatformMetrics metrics;

    private Map<String, Object> row(UUID eventId) {
        return jdbc.queryForMap(
                "SELECT status, attempts, last_error, next_attempt_at FROM platform.outbox_events WHERE event_id = ?",
                eventId);
    }

    @Test
    void failingTopicDoesNotBlockOtherTenantsInTheSameCycle() throws Exception {
        drainOutbox();
        Instant base = Instant.now().minusSeconds(30);
        // claim order interleaves the tenants: the failing topic is hit first
        UUID a1 = newOutboxRow(TENANT_A, "DEV-A1", base);
        UUID b1 = newOutboxRow(TENANT_B, "DEV-B1", base.plusSeconds(1));
        UUID a2 = newOutboxRow(TENANT_A, "DEV-A2", base.plusSeconds(2));
        UUID b2 = newOutboxRow(TENANT_B, "DEV-B2", base.plusSeconds(3));
        toggling.setSender(message -> {
            if (message.topic().equals(TENANT_A + ".readings")) {
                throw new PublishException("topic unavailable", null);
            }
            return CompletableFuture.completedFuture(null);
        });

        assertThat(outboxPublisher.publishDue()).isEqualTo(2);

        assertThat(row(b1).get("status")).isEqualTo("PUBLISHED");
        assertThat(row(b2).get("status")).isEqualTo("PUBLISHED");
        Map<String, Object> failed = row(a1);
        assertThat(failed.get("status")).isEqualTo("PENDING");
        assertThat(((Number) failed.get("attempts")).intValue()).isEqualTo(1);
        assertThat((String) failed.get("last_error")).contains("topic unavailable");
        Instant backoffUntil = ((Timestamp) failed.get("next_attempt_at")).toInstant();
        assertThat(backoffUntil).isAfter(Instant.now()).isBefore(Instant.now().plusSeconds(10));
        // same topic, not sent in this cycle: same backoff, no attempt counted
        Map<String, Object> skipped = row(a2);
        assertThat(((Number) skipped.get("attempts")).intValue()).isZero();
        assertThat(((Timestamp) skipped.get("next_attempt_at")).toInstant()).isEqualTo(backoffUntil);
    }

    @Test
    void sendsAreIssuedForTheWholeBatchBeforeAnyAcknowledgementIsAwaited() throws Exception {
        drainOutbox();
        int rows = 5;
        Instant base = Instant.now().minusSeconds(30);
        List<UUID> events = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            events.add(newOutboxRow(TENANT_B, "DEV-S" + i, base.plusSeconds(i)));
        }
        List<CompletableFuture<Void>> pending = new CopyOnWriteArrayList<>();
        // the broker "acknowledges" only once every row of the batch has been handed over
        toggling.setSender(message -> {
            CompletableFuture<Void> ack = new CompletableFuture<>();
            pending.add(ack);
            if (pending.size() == rows) {
                pending.forEach(f -> f.complete(null));
            }
            return ack;
        });

        assertThat(outboxPublisher.publishDue()).isEqualTo(rows);

        events.forEach(e -> assertThat(row(e).get("status")).isEqualTo("PUBLISHED"));
    }

    @Test
    void issuingStopsAtTheDeadlineAndReleasesUnsentRows() throws Exception {
        drainOutbox();
        Instant base = Instant.now().minusSeconds(30);
        List<UUID> events = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            events.add(newOutboxRow(TENANT_B, "DEV-D" + i, base.plusSeconds(i)));
        }
        AdjustableClock clock = new AdjustableClock(Instant.now());
        OutboxPublisher publisher = new OutboxPublisher(
                outboxRepository,
                toggling,
                new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(30)),
                metrics,
                tx,
                clock,
                new OutboxProperties(true, 1000, 50, 50, 1000, 30000, Duration.ofSeconds(60), Duration.ofSeconds(10)),
                new PlatformKafkaProperties(3, (short) 1, 5000, 5000),
                springKafka(5000),
                tracing);
        List<String> sent = new ArrayList<>();
        // sending the second row takes "longer" than the issue deadline
        toggling.setSender(message -> {
            sent.add(message.eventId());
            if (sent.size() == 2) {
                clock.advance(Duration.ofSeconds(12));
            }
            return CompletableFuture.completedFuture(null);
        });

        assertThat(publisher.publishDue()).isEqualTo(2);

        assertThat(sent).containsExactly(events.get(0).toString(), events.get(1).toString());
        for (int i = 0; i < 2; i++) {
            assertThat(row(events.get(i)).get("status")).isEqualTo("PUBLISHED");
        }
        for (int i = 2; i < 4; i++) {
            Map<String, Object> released = row(events.get(i));
            assertThat(released.get("status")).isEqualTo("PENDING");
            assertThat(((Number) released.get("attempts")).intValue()).isZero();
            // due again now, not held until the 60 s lease the claim set
            assertThat(((Timestamp) released.get("next_attempt_at")).toInstant())
                    .isBefore(clock.instant().plusMillis(1));
        }
    }

    @Test
    void sendsThatFailOrNeverCompleteAfterIssueChargeAnAttemptAndLeaveTheRestPublished() throws Exception {
        drainOutbox();
        Instant base = Instant.now().minusSeconds(30);
        List<UUID> events = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            events.add(newOutboxRow(TENANT_B, "DEV-F" + i, base.plusSeconds(i)));
        }
        OutboxPublisher publisher = new OutboxPublisher(
                outboxRepository,
                toggling,
                new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(30)),
                metrics,
                tx,
                Clock.systemUTC(),
                new OutboxProperties(true, 1000, 50, 50, 1000, 30000, Duration.ofSeconds(60), Duration.ofSeconds(10)),
                new PlatformKafkaProperties(3, (short) 1, 1500, 1500),
                springKafka(5000),
                tracing);
        List<UUID> order = new ArrayList<>(events);
        CompletableFuture<Void> brokerRejection = new CompletableFuture<>();
        // the broker rejects the second record only after all four were issued, so it is not an immediate failure
        toggling.setSender(message -> {
            int index = order.indexOf(UUID.fromString(message.eventId()));
            if (index == 3) {
                brokerRejection.completeExceptionally(new PublishException("broker rejected record", null));
            }
            return switch (index) {
                case 1 -> brokerRejection;
                case 2 -> new CompletableFuture<>(); // never acknowledged
                default -> CompletableFuture.completedFuture(null);
            };
        });

        assertThat(publisher.publishDue()).isEqualTo(2);

        assertThat(row(events.get(0)).get("status")).isEqualTo("PUBLISHED");
        assertThat(row(events.get(3)).get("status")).isEqualTo("PUBLISHED");
        Map<String, Object> rejected = row(events.get(1));
        Map<String, Object> unacknowledged = row(events.get(2));
        assertThat((String) rejected.get("last_error")).contains("broker rejected record");
        assertThat((String) unacknowledged.get("last_error")).contains("timed out");
        for (Map<String, Object> failed : List.of(rejected, unacknowledged)) {
            assertThat(failed.get("status")).isEqualTo("PENDING");
            assertThat(((Number) failed.get("attempts")).intValue()).isEqualTo(1);
            // short backoff after the failure, not the 60 s lease the claim set
            assertThat(((Timestamp) failed.get("next_attempt_at")).toInstant())
                    .isBefore(Instant.now().plusSeconds(10));
        }
    }

    /** Clock whose time only moves when the test says so. */
    private static final class AdjustableClock extends Clock {

        private final AtomicReference<Instant> now;

        AdjustableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration by) {
            now.updateAndGet(i -> i.plus(by));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
