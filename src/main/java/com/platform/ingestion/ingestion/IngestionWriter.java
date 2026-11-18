package com.platform.ingestion.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.ingestion.IngestionDtos.IngestionResponse;
import com.platform.ingestion.persistence.IngestionRecord;
import com.platform.ingestion.persistence.IngestionRecordRepository;
import com.platform.ingestion.persistence.OutboxEvent;
import com.platform.ingestion.persistence.OutboxRepository;
import com.platform.ingestion.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single database transaction of an ingestion: idempotency claim + ingestion record + outbox row.
 * The HTTP 202 is only produced after this method returned, i.e. after the commit.
 */
@Service
public class IngestionWriter {

    public record WriteRequest(
            TenantContext tenant,
            String idempotencyKey,
            String requestHash,
            UUID ingestionId,
            UUID eventId,
            String deviceId,
            UUID contractVersionId,
            JsonNode rawPayload,
            ObjectNode canonicalEvent,
            Instant receivedAt) {}

    /** {@code duplicate=true} means the key was already claimed by an identical request. */
    public record WriteOutcome(boolean duplicate, UUID ingestionId) {}

    private final IdempotencyService idempotency;
    private final IngestionRecordRepository records;
    private final OutboxRepository outbox;

    public IngestionWriter(IdempotencyService idempotency, IngestionRecordRepository records, OutboxRepository outbox) {
        this.idempotency = idempotency;
        this.records = records;
        this.outbox = outbox;
    }

    @Transactional
    public WriteOutcome write(WriteRequest r) {
        var existing = idempotency.claim(
                r.tenant().id(), r.idempotencyKey(), r.requestHash(), r.ingestionId(), r.receivedAt());
        if (existing.isPresent()) {
            return new WriteOutcome(true, existing.get());
        }
        records.save(new IngestionRecord(
                r.ingestionId(),
                r.tenant().id(),
                r.eventId(),
                r.idempotencyKey(),
                r.deviceId(),
                r.contractVersionId(),
                r.rawPayload(),
                r.canonicalEvent(),
                r.receivedAt()));
        outbox.save(new OutboxEvent(
                UUID.randomUUID(),
                r.tenant().id(),
                r.eventId(),
                r.tenant().kafkaTopic(),
                r.deviceId(),
                r.canonicalEvent(),
                r.receivedAt()));
        return new WriteOutcome(false, r.ingestionId());
    }

    /** Rebuilds the response of an earlier ingestion (idempotent replay). Tenant-filtered. */
    @Transactional(readOnly = true)
    public IngestionResponse replayResponse(UUID tenantId, UUID ingestionId) {
        IngestionRecord record = records.findByIdAndTenantId(ingestionId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Ingestion"));
        JsonNode event = record.getCanonicalEvent();
        return new IngestionResponse(
                "DUPLICATE",
                record.getId(),
                record.getEventId(),
                event.path("tenant_id").asText(),
                UUID.fromString(event.path("contract_id").asText()),
                event.path("contract_version").asInt(),
                record.getReceivedAt());
    }
}
