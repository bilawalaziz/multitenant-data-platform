package com.platform.ingestion.contract;

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

@Entity
@Table(name = "contract_mappings")
public class ContractMapping extends PersistableEntity {

    @Id
    private UUID id;

    @Column(name = "contract_version_id", nullable = false, updatable = false)
    private UUID contractVersionId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode mapping;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ContractMapping() {}

    public ContractMapping(UUID id, UUID contractVersionId, UUID tenantId, JsonNode mapping, Instant createdAt) {
        this.id = id;
        this.contractVersionId = contractVersionId;
        this.tenantId = tenantId;
        this.mapping = mapping;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getContractVersionId() {
        return contractVersionId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public JsonNode getMapping() {
        return mapping;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
