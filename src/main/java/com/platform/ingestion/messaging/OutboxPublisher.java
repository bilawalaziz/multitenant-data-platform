package com.platform.ingestion.messaging;

import com.platform.ingestion.observability.MdcKeys;
import com.platform.ingestion.observability.PlatformMetrics;
import com.platform.ingestion.persistence.OutboxEvent;
import com.platform.ingestion.persistence.OutboxRepository;
import com.platform.ingestion.persistence.OutboxStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes due outbox rows with at-least-once delivery. Rows are claimed with FOR UPDATE SKIP LOCKED in a short
 * transaction that also pushes next_attempt_at forward by {@code platform.outbox.lease}, so other instances skip
 * them. Kafka is called outside any transaction. The batch stops at the first failure (the broker is probably
 * down) and the remaining claimed rows are released. Each outcome is a targeted update that only applies while the
 * row is still PENDING, so a concurrent requeue is not overwritten.
 *
 * If the process dies after Kafka accepted a message but before the row is marked, the lease expires and the row is
 * published again. Consumers deduplicate on event_id. The lease must be longer than a batch takes to publish.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    /** Immutable view of a claimed row, safe to use outside the claiming transaction. */
    record Claim(
            UUID id,
            UUID eventId,
            String topic,
            String key,
            String payload,
            String tenantCode,
            String schemaVersion,
            int attempts) {

        OutboxMessage toMessage() {
            return new OutboxMessage(topic, key, payload, eventId.toString(), tenantCode, schemaVersion);
        }
    }

    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final BackoffPolicy backoff;
    private final PlatformMetrics metrics;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final OutboxProperties props;

    public OutboxPublisher(
            OutboxRepository outbox,
            EventPublisher publisher,
            BackoffPolicy backoff,
            PlatformMetrics metrics,
            TransactionTemplate tx,
            Clock clock,
            OutboxProperties props) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.backoff = backoff;
        this.metrics = metrics;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
    }

    /** @return number of rows published in this run. */
    public int publishDue() {
        List<Claim> claimed = claim();
        int count = 0;
        for (int i = 0; i < claimed.size(); i++) {
            Claim claim = claimed.get(i);
            MDC.put(MdcKeys.EVENT_ID, claim.eventId().toString());
            try {
                publisher.publish(claim.toMessage());
            } catch (RuntimeException e) {
                recordFailure(claim, e, claimed.subList(i + 1, claimed.size()));
                break;
            } finally {
                MDC.remove(MdcKeys.EVENT_ID);
            }
            metrics.outboxPublishSucceeded();
            count++;
            Instant now = clock.instant();
            tx.executeWithoutResult(s -> outbox.markPublished(claim.id(), now));
        }
        return count;
    }

    private List<Claim> claim() {
        List<Claim> claims = tx.execute(status -> {
            Instant now = clock.instant();
            Instant leaseUntil = now.plus(props.lease());
            List<OutboxEvent> due = outbox.claimDue(now, props.batchSize());
            List<Claim> result = due.stream().map(OutboxPublisher::toClaim).toList();
            due.forEach(e -> e.lease(leaseUntil));
            return result;
        });
        return claims == null ? List.of() : claims;
    }

    private void recordFailure(Claim claim, RuntimeException e, List<Claim> untouched) {
        int attempt = claim.attempts() + 1;
        boolean exhausted = attempt >= props.maxAttempts();
        Instant next = clock.instant().plus(backoff.delay(attempt));
        String message = e.getMessage() == null ? "unknown error" : e.getMessage();
        String error = message.substring(0, Math.min(message.length(), 1000));
        tx.executeWithoutResult(s -> {
            outbox.recordFailedAttempt(claim.id(), error, next, exhausted ? OutboxStatus.FAILED : OutboxStatus.PENDING);
            if (!untouched.isEmpty()) {
                outbox.releaseLease(untouched.stream().map(Claim::id).toList(), next);
            }
        });
        metrics.outboxPublishFailed();
        if (exhausted) {
            log.error("Outbox event {} failed permanently after {} attempts: {}", claim.id(), attempt, e.getMessage());
        } else {
            log.warn("Outbox publish failed (attempt {}), will retry: {}", attempt, e.getMessage());
        }
    }

    private static Claim toClaim(OutboxEvent event) {
        return new Claim(
                event.getId(),
                event.getEventId(),
                event.getTopic(),
                event.getMessageKey(),
                event.getPayload().toString(),
                event.getPayload().path("tenant_id").asText(null),
                event.getPayload().path("schema_version").asText(null),
                event.getAttempts());
    }
}
