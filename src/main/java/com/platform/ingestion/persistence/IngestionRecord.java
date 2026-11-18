package com.platform.ingestion.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.ingestion.common.PersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Accepted ingestion: the raw tenant payload plus the canonical event derived from it. */
@Entity
@Table(name = "ingestion_records")
public class IngestionRecord extends PersistableEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "device_id", nullable = false, updatable = false)
    private String deviceId;

    @Column(name = "contract_version_id", nullable = false, updatable = false)
    private UUID contractVersionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode rawPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "canonical_event", nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode canonicalEvent;

    @Column(nullable = false)
    private String status;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected IngestionRecord() {}

    public IngestionRecord(
            UUID id,
            UUID tenantId,
            UUID eventId,
            String idempotencyKey,
            String deviceId,
            UUID contractVersionId,
            JsonNode rawPayload,
            JsonNode canonicalEvent,
            Instant receivedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.eventId = eventId;
        this.idempotencyKey = idempotencyKey;
        this.deviceId = deviceId;
        this.contractVersionId = contractVersionId;
        this.rawPayload = rawPayload;
        this.canonicalEvent = canonicalEvent;
        this.status = "ACCEPTED";
        this.receivedAt = receivedAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public UUID getContractVersionId() {
        return contractVersionId;
    }

    public JsonNode getRawPayload() {
        return rawPayload;
    }

    public JsonNode getCanonicalEvent() {
        return canonicalEvent;
    }

    public String getStatus() {
        return status;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
