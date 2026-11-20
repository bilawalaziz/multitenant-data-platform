package com.platform.ingestion.messaging;

import com.platform.ingestion.observability.MdcKeys;
import com.platform.ingestion.observability.PlatformMetrics;
import com.platform.ingestion.persistence.OutboxEvent;
import com.platform.ingestion.persistence.OutboxRepository;
import com.platform.ingestion.persistence.OutboxStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * At-least-once outbox publisher. Rows are claimed with FOR UPDATE SKIP LOCKED in a short transaction that also
 * pushes next_attempt_at forward by the lease, so other instances skip them; Kafka is called outside any transaction.
 *
 * <p>A cycle issues all sends, then awaits them and records outcomes in one transaction. A topic whose send
 * fails immediately is skipped for the rest of the cycle so one tenant cannot hold up the others. Issuing stops at
 * the issue deadline and unsent rows are released. Outcome updates only apply to PENDING rows, so a concurrent
 * requeue is not overwritten. The lease must exceed issue deadline plus send timeout (checked at startup).
 * Consumers deduplicate on event_id.
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

    private record InFlight(Claim claim, CompletableFuture<Void> future) {}

    private record Failure(Claim claim, String message, Instant next) {}

    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final BackoffPolicy backoff;
    private final PlatformMetrics metrics;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final OutboxProperties props;
    private final Duration sendTimeout;

    public OutboxPublisher(
            OutboxRepository outbox,
            EventPublisher publisher,
            BackoffPolicy backoff,
            PlatformMetrics metrics,
            TransactionTemplate tx,
            Clock clock,
            OutboxProperties props,
            PlatformKafkaProperties kafkaProps,
            KafkaProperties springKafka) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.backoff = backoff;
        this.metrics = metrics;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
        this.sendTimeout = Duration.ofMillis(kafkaProps.sendTimeoutMs());
        // One send can still block after the deadline check: topic provisioning plus the producer metadata wait
        Duration maxSendBlock = Duration.ofMillis(kafkaProps.adminTimeoutMs()).plus(producerMaxBlock(springKafka));
        if (props.issueDeadline().plus(maxSendBlock).plus(sendTimeout).compareTo(props.lease()) >= 0) {
            throw new IllegalStateException("platform.outbox.lease (" + props.lease()
                    + ") must be longer than platform.outbox.issue-deadline (" + props.issueDeadline()
                    + ") plus the longest blocking send (" + maxSendBlock
                    + ": platform.kafka.admin-timeout-ms plus producer max.block.ms) plus platform.kafka.send-timeout-ms ("
                    + sendTimeout + ")");
        }
    }

    private static Duration producerMaxBlock(KafkaProperties springKafka) {
        String configured = springKafka.getProducer().getProperties().get("max.block.ms");
        // Kafka's own default when the application does not set it
        return Duration.ofMillis(configured == null ? 60_000 : Long.parseLong(configured.trim()));
    }

    /** @return number of rows published in this run. */
    public int publishDue() {
        List<Claim> claimed = claim();
        if (claimed.isEmpty()) {
            return 0;
        }
        Instant issueUntil = clock.instant().plus(props.issueDeadline());

        List<InFlight> inFlight = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        List<Claim> unissued = new ArrayList<>();
        // topic -> backoff of the row that failed immediately; later rows of that topic are not sent
        Map<String, Instant> failedTopics = new HashMap<>();
        Map<String, List<Claim>> skippedByTopic = new HashMap<>();
        boolean deadlineReached = false;

        for (Claim claim : claimed) {
            if (!deadlineReached && !clock.instant().isBefore(issueUntil)) {
                deadlineReached = true;
            }
            if (deadlineReached) {
                unissued.add(claim);
                continue;
            }
            if (failedTopics.containsKey(claim.topic())) {
                skippedByTopic
                        .computeIfAbsent(claim.topic(), t -> new ArrayList<>())
                        .add(claim);
                continue;
            }
            CompletableFuture<Void> future = issue(claim);
            if (future.isCompletedExceptionally()) {
                Failure failure = failure(claim, describe(future));
                failures.add(failure);
                failedTopics.put(claim.topic(), failure.next());
            } else {
                inFlight.add(new InFlight(claim, future));
            }
        }

        List<UUID> publishedIds = new ArrayList<>();
        List<Claim> unfinished = new ArrayList<>();
        awaitAll(inFlight, publishedIds, failures, unfinished);
        unissued.addAll(unfinished);
        if (!unissued.isEmpty()) {
            log.warn("Outbox issue deadline reached, releasing {} unsent rows", unissued.size());
        }

        Instant now = clock.instant();
        tx.executeWithoutResult(s -> {
            if (!publishedIds.isEmpty()) {
                outbox.markPublishedAll(publishedIds, now);
            }
            failures.forEach(this::recordFailure);
            skippedByTopic.forEach((topic, rows) ->
                    outbox.releaseLease(rows.stream().map(Claim::id).toList(), failedTopics.get(topic)));
            if (!unissued.isEmpty()) {
                outbox.releaseLease(unissued.stream().map(Claim::id).toList(), now);
            }
        });
        publishedIds.forEach(id -> metrics.outboxPublishSucceeded());
        failures.forEach(f -> metrics.outboxPublishFailed());
        return publishedIds.size();
    }

    private CompletableFuture<Void> issue(Claim claim) {
        MDC.put(MdcKeys.EVENT_ID, claim.eventId().toString());
        try {
            CompletableFuture<Void> future = publisher.publish(claim.toMessage());
            return future == null
                    ? CompletableFuture.failedFuture(new PublishException("publisher returned no result", null))
                    : future;
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            MDC.remove(MdcKeys.EVENT_ID);
        }
    }

    /** Waits for every issued send, together at most the send timeout, and sorts the rows by outcome. */
    private void awaitAll(
            List<InFlight> inFlight, List<UUID> published, List<Failure> failures, List<Claim> unfinished) {
        Instant giveUp = clock.instant().plus(sendTimeout);
        boolean interrupted = false;
        for (InFlight sent : inFlight) {
            if (interrupted) {
                settleWithoutWaiting(sent, published, failures, unfinished);
                continue;
            }
            long remainingMs =
                    Math.max(Duration.between(clock.instant(), giveUp).toMillis(), 0);
            try {
                sent.future().get(remainingMs, TimeUnit.MILLISECONDS);
                published.add(sent.claim().id());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                failures.add(failure(sent.claim(), cause.getMessage()));
            } catch (TimeoutException e) {
                failures.add(failure(sent.claim(), "Kafka publish timed out after " + sendTimeout.toMillis() + " ms"));
            } catch (InterruptedException e) {
                // stop waiting; rows still in flight are released without charging an attempt
                Thread.currentThread().interrupt();
                interrupted = true;
                settleWithoutWaiting(sent, published, failures, unfinished);
            }
        }
    }

    private void settleWithoutWaiting(
            InFlight sent, List<UUID> published, List<Failure> failures, List<Claim> unfinished) {
        if (!sent.future().isDone()) {
            unfinished.add(sent.claim());
        } else if (sent.future().isCompletedExceptionally()) {
            failures.add(failure(sent.claim(), describe(sent.future())));
        } else {
            published.add(sent.claim().id());
        }
    }

    private Failure failure(Claim claim, String message) {
        Instant next = clock.instant().plus(backoff.delay(claim.attempts() + 1));
        return new Failure(claim, message == null ? "unknown error" : message, next);
    }

    private static String describe(CompletableFuture<Void> failed) {
        try {
            failed.join();
            return "unknown error";
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return cause.getMessage();
        }
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

    private void recordFailure(Failure failure) {
        Claim claim = failure.claim();
        int attempt = claim.attempts() + 1;
        boolean exhausted = attempt >= props.maxAttempts();
        String error = failure.message().substring(0, Math.min(failure.message().length(), 1000));
        outbox.recordFailedAttempt(
                claim.id(), error, failure.next(), exhausted ? OutboxStatus.FAILED : OutboxStatus.PENDING);
        if (exhausted) {
            log.error(
                    "Outbox event {} failed permanently after {} attempts: {}", claim.id(), attempt, failure.message());
        } else {
            log.warn(
                    "Outbox publish of event {} failed (attempt {}), will retry: {}",
                    claim.eventId(),
                    attempt,
                    failure.message());
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
