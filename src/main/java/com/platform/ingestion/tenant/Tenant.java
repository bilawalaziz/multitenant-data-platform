package com.platform.ingestion.tenant;

import com.platform.ingestion.common.PersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class Tenant extends PersistableEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_code", nullable = false, updatable = false)
    private String tenantCode;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantStatus status;

    @Column(name = "schema_name", nullable = false, updatable = false)
    private String schemaName;

    @Column(name = "kafka_topic", nullable = false, updatable = false)
    private String kafkaTopic;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Tenant() {}

    public Tenant(
            UUID id,
            String tenantCode,
            String displayName,
            TenantStatus status,
            String schemaName,
            String kafkaTopic,
            Instant createdAt) {
        this.id = id;
        this.tenantCode = tenantCode;
        this.displayName = displayName;
        this.status = status;
        this.schemaName = schemaName;
        this.kafkaTopic = kafkaTopic;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getTenantCode() {
        return tenantCode;
    }

    public String getDisplayName() {
        return displayName;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public String getKafkaTopic() {
        return kafkaTopic;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public TenantContext toContext() {
        return new TenantContext(id, tenantCode, schemaName, kafkaTopic);
    }
}
