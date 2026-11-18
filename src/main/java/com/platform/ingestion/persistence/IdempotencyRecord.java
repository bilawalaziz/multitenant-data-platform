package com.platform.ingestion.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Idempotency claim scoped to (tenant, key). Inserted via native ON CONFLICT DO NOTHING, read via JPA. */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "tenant_id", nullable = false)
        private UUID tenantId;

        @Column(name = "idempotency_key", nullable = false)
        private String idempotencyKey;

        protected Key() {}

        public Key(UUID tenantId, String idempotencyKey) {
            this.tenantId = tenantId;
            this.idempotencyKey = idempotencyKey;
        }

        public UUID getTenantId() {
            return tenantId;
        }

        public String getIdempotencyKey() {
            return idempotencyKey;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k
                    && Objects.equals(tenantId, k.tenantId)
                    && Objects.equals(idempotencyKey, k.idempotencyKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, idempotencyKey);
        }
    }

    @EmbeddedId
    private Key key;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "ingestion_id", nullable = false)
    private UUID ingestionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {}

    public Key getKey() {
        return key;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getIngestionId() {
        return ingestionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
