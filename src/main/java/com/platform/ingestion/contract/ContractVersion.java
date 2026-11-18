package com.platform.ingestion.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.ingestion.common.PersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable contract version; only {@code status} and {@code activatedAt} ever change. */
@Entity
@Table(name = "contract_versions")
public class ContractVersion extends PersistableEntity {

    @Id
    private UUID id;

    @Column(name = "contract_id", nullable = false, updatable = false)
    private UUID contractId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private int version;

    @Column(name = "spec_format", nullable = false, updatable = false)
    private String specFormat;

    @Column(name = "spec_content", nullable = false, updatable = false, columnDefinition = "text")
    private String specContent;

    @Column(name = "checksum_sha256", nullable = false, updatable = false)
    private String checksumSha256;

    @Column(name = "ingestion_path", nullable = false, updatable = false)
    private String ingestionPath;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_schema", nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode requestSchema;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContractStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    protected ContractVersion() {}

    public ContractVersion(
            UUID id,
            UUID contractId,
            UUID tenantId,
            int version,
            String specFormat,
            String specContent,
            String checksumSha256,
            String ingestionPath,
            JsonNode requestSchema,
            Instant createdAt) {
        this.id = id;
        this.contractId = contractId;
        this.tenantId = tenantId;
        this.version = version;
        this.specFormat = specFormat;
        this.specContent = specContent;
        this.checksumSha256 = checksumSha256;
        this.ingestionPath = ingestionPath;
        this.requestSchema = requestSchema;
        this.status = ContractStatus.DRAFT;
        this.createdAt = createdAt;
    }

    public void activate(Instant now) {
        this.status = ContractStatus.ACTIVE;
        this.activatedAt = now;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getContractId() {
        return contractId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public int getVersion() {
        return version;
    }

    public String getSpecFormat() {
        return specFormat;
    }

    public String getSpecContent() {
        return specContent;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public String getIngestionPath() {
        return ingestionPath;
    }

    public JsonNode getRequestSchema() {
        return requestSchema;
    }

    public ContractStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }
}
