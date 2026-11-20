package com.platform.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.ingestion.messaging.PublishException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Lease-based outbox claim: nothing is locked while publishing, outcomes are committed, leases expire. */
class OutboxLeaseIT extends AbstractIntegrationTest {

    private static final String TENANT = "tenant-lease";

    @Autowired
    private DataSource dataSource;

    private UUID newRow(String device, Instant createdAt) throws Exception {
        return newOutboxRow(TENANT, device, createdAt);
    }

    @Test
    void noTransactionOrRowLockIsHeldWhilePublishing() throws Exception {
        drainOutbox();
        UUID eventId = newRow("LEASE-1", Instant.now().minusSeconds(5));
        boolean[] checked = {false};
        toggling.setInterceptor(message -> {
            // separate connection and transaction: must be able to lock the row immediately
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement ps =
                        c.prepareStatement("SELECT status, next_attempt_at FROM platform.outbox_events "
                                + "WHERE event_id = ? FOR UPDATE NOWAIT")) {
                    ps.setObject(1, eventId);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("status")).isEqualTo("PENDING");
                        // leased: pushed into the future by the committed claim transaction
                        assertThat(rs.getTimestamp("next_attempt_at").toInstant())
                                .isAfter(Instant.now().plusSeconds(10));
                    }
                }
                c.rollback();
                checked[0] = true;
            } catch (Exception e) {
                throw new AssertionError("row was locked or unreadable while publishing", e);
            }
        });

        assertThat(outboxPublisher.publishDue()).isEqualTo(1);

        assertThat(checked[0]).isTrue();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, published_at, attempts FROM platform.outbox_events WHERE event_id = ?", eventId);
        assertThat(row.get("status")).isEqualTo("PUBLISHED");
        assertThat(row.get("published_at")).isNotNull();
    }

    @Test
    void failedAttemptIsCommittedAndSkippedRowOfSameTopicIsReleasedWithoutAnAttempt() throws Exception {
        drainOutbox();
        Instant base = Instant.now().minusSeconds(20);
        UUID first = newRow("LEASE-A", base);
        UUID second = newRow("LEASE-B", base.plusSeconds(1));
        toggling.setInterceptor(message -> {
            throw new PublishException("simulated broker outage", null);
        });

        assertThat(outboxPublisher.publishDue()).isZero();

        Map<String, Object> a = jdbc.queryForMap(
                "SELECT status, attempts, last_error, next_attempt_at FROM platform.outbox_events WHERE event_id = ?",
                first);
        assertThat(a.get("status")).isEqualTo("PENDING");
        assertThat(((Number) a.get("attempts")).intValue()).isEqualTo(1);
        assertThat((String) a.get("last_error")).contains("simulated broker outage");
        Instant firstNext = ((Timestamp) a.get("next_attempt_at")).toInstant();
        assertThat(firstNext).isAfter(Instant.now());
        assertThat(firstNext).isBefore(Instant.now().plusSeconds(10)); // backoff, not the 30 s lease

        Map<String, Object> b = jdbc.queryForMap(
                "SELECT status, attempts, next_attempt_at FROM platform.outbox_events WHERE event_id = ?", second);
        assertThat(b.get("status")).isEqualTo("PENDING");
        assertThat(((Number) b.get("attempts")).intValue()).isZero(); // never sent, so no attempt counted
        assertThat(((Timestamp) b.get("next_attempt_at")).toInstant())
                .isEqualTo(firstNext); // released, not leased 30 s
    }

    @Test
    void leasedRowIsSkippedUntilLeaseExpiresThenRepublished() throws Exception {
        drainOutbox();
        UUID eventId = newRow("LEASE-C", Instant.now().minusSeconds(5));
        List<String> published = new ArrayList<>();
        toggling.setInterceptor(message -> published.add(message.eventId()));

        // a crashed instance claimed the row (lease in the future) and never marked it
        jdbc.update(
                "UPDATE platform.outbox_events SET next_attempt_at = now() + interval '30 seconds' WHERE event_id = ?",
                eventId);
        assertThat(outboxPublisher.publishDue()).isZero();
        assertThat(published).isEmpty();

        // once the lease expires the row is due again and is republished (at-least-once)
        jdbc.update(
                "UPDATE platform.outbox_events SET next_attempt_at = now() - interval '1 second' WHERE event_id = ?",
                eventId);
        assertThat(outboxPublisher.publishDue()).isEqualTo(1);
        assertThat(published).containsExactly(eventId.toString());
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM platform.outbox_events WHERE event_id = ?", String.class, eventId))
                .isEqualTo("PUBLISHED");
    }
}
