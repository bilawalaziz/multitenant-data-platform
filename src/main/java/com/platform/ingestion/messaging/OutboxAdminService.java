package com.platform.ingestion.messaging;

import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.persistence.OutboxEvent;
import com.platform.ingestion.persistence.OutboxRepository;
import com.platform.ingestion.persistence.OutboxStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxAdminService {

    public record OutboxEventResponse(
            UUID id,
            UUID tenantId,
            UUID eventId,
            String topic,
            String messageKey,
            String status,
            int attempts,
            Instant nextAttemptAt,
            String lastError,
            Instant createdAt,
            Instant publishedAt) {

        static OutboxEventResponse from(OutboxEvent e) {
            return new OutboxEventResponse(
                    e.getId(),
                    e.getTenantId(),
                    e.getEventId(),
                    e.getTopic(),
                    e.getMessageKey(),
                    e.getStatus().name(),
                    e.getAttempts(),
                    e.getNextAttemptAt(),
                    e.getLastError(),
                    e.getCreatedAt(),
                    e.getPublishedAt());
        }
    }

    public record OutboxStats(long pending, long published, long failed, Long oldestPendingAgeSeconds) {}

    private final OutboxRepository outbox;
    private final Clock clock;

    public OutboxAdminService(OutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<OutboxEventResponse> list(OutboxStatus status, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 500)));
        List<OutboxEvent> rows = status == null
                ? outbox.findAllByOrderByCreatedAtDesc(page)
                : outbox.findByStatusOrderByCreatedAtDesc(status, page);
        return rows.stream().map(OutboxEventResponse::from).toList();
    }

    @Transactional
    public OutboxEventResponse requeue(UUID id) {
        OutboxEvent event = outbox.findById(id).orElseThrow(() -> ApiException.notFound("Outbox event"));
        if (event.getStatus() == OutboxStatus.PUBLISHED) {
            throw new ApiException(ErrorCode.CONFLICT, "Outbox event has already been published");
        }
        event.requeue(clock.instant());
        return OutboxEventResponse.from(outbox.saveAndFlush(event));
    }

    @Transactional(readOnly = true)
    public OutboxStats stats() {
        Instant oldest = outbox.oldestPendingCreatedAt();
        Long age = oldest == null
                ? null
                : Duration.between(oldest, clock.instant()).toSeconds();
        return new OutboxStats(
                outbox.countByStatus(OutboxStatus.PENDING),
                outbox.countByStatus(OutboxStatus.PUBLISHED),
                outbox.countByStatus(OutboxStatus.FAILED),
                age);
    }
}
