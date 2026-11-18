-- Shared platform schema. Tenant-owned tables always carry tenant_id.

CREATE TABLE platform.tenants (
    id            uuid PRIMARY KEY,
    tenant_code   varchar(31)  NOT NULL UNIQUE,
    display_name  varchar(120) NOT NULL,
    status        varchar(16)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    schema_name   varchar(40)  NOT NULL UNIQUE,
    kafka_topic   varchar(60)  NOT NULL UNIQUE,
    created_at    timestamptz  NOT NULL
);

CREATE TABLE platform.devices (
    id           uuid PRIMARY KEY,
    tenant_id    uuid         NOT NULL REFERENCES platform.tenants (id),
    device_id    varchar(64)  NOT NULL,
    description  varchar(255),
    status       varchar(16)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at   timestamptz  NOT NULL,
    CONSTRAINT uq_devices_tenant_device UNIQUE (tenant_id, device_id)
);

CREATE TABLE platform.contracts (
    id          uuid PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES platform.tenants (id),
    name        varchar(100) NOT NULL,
    created_at  timestamptz  NOT NULL,
    CONSTRAINT uq_contracts_tenant_name UNIQUE (tenant_id, name)
);

CREATE TABLE platform.contract_versions (
    id               uuid PRIMARY KEY,
    contract_id      uuid         NOT NULL REFERENCES platform.contracts (id),
    tenant_id        uuid         NOT NULL REFERENCES platform.tenants (id),
    version          integer      NOT NULL,
    spec_format      varchar(8)   NOT NULL CHECK (spec_format IN ('JSON', 'YAML')),
    spec_content     text         NOT NULL,
    checksum_sha256  varchar(64)  NOT NULL,
    ingestion_path   varchar(200) NOT NULL,
    request_schema   jsonb        NOT NULL,
    status           varchar(16)  NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE')),
    created_at       timestamptz  NOT NULL,
    activated_at     timestamptz,
    CONSTRAINT uq_contract_versions_contract_version UNIQUE (contract_id, version)
);

-- At most one ACTIVE contract version per tenant.
CREATE UNIQUE INDEX uq_contract_versions_one_active_per_tenant
    ON platform.contract_versions (tenant_id) WHERE status = 'ACTIVE';

CREATE INDEX idx_contract_versions_tenant ON platform.contract_versions (tenant_id);

CREATE TABLE platform.contract_mappings (
    id                   uuid PRIMARY KEY,
    contract_version_id  uuid        NOT NULL UNIQUE REFERENCES platform.contract_versions (id),
    tenant_id            uuid        NOT NULL REFERENCES platform.tenants (id),
    mapping              jsonb       NOT NULL,
    created_at           timestamptz NOT NULL
);

CREATE TABLE platform.ingestion_records (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid         NOT NULL REFERENCES platform.tenants (id),
    event_id              uuid         NOT NULL UNIQUE,
    idempotency_key       varchar(128) NOT NULL,
    device_id             varchar(64)  NOT NULL,
    contract_version_id   uuid         NOT NULL REFERENCES platform.contract_versions (id),
    raw_payload           jsonb        NOT NULL,
    canonical_event       jsonb        NOT NULL,
    status                varchar(16)  NOT NULL,
    received_at           timestamptz  NOT NULL
);

CREATE INDEX idx_ingestion_records_tenant_received ON platform.ingestion_records (tenant_id, received_at DESC);

CREATE TABLE platform.idempotency_records (
    tenant_id        uuid         NOT NULL REFERENCES platform.tenants (id),
    idempotency_key  varchar(128) NOT NULL,
    request_hash     varchar(64)  NOT NULL,
    ingestion_id     uuid         NOT NULL,
    created_at       timestamptz  NOT NULL,
    PRIMARY KEY (tenant_id, idempotency_key),
    -- Deferred so the idempotency claim can be inserted before the ingestion record in the same transaction.
    CONSTRAINT fk_idempotency_ingestion FOREIGN KEY (ingestion_id)
        REFERENCES platform.ingestion_records (id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE platform.outbox_events (
    id               uuid PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES platform.tenants (id),
    event_id         uuid         NOT NULL,
    topic            varchar(100) NOT NULL,
    message_key      varchar(128) NOT NULL,
    payload          jsonb        NOT NULL,
    status           varchar(16)  NOT NULL CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    attempts         integer      NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz  NOT NULL,
    last_error       varchar(1000),
    created_at       timestamptz  NOT NULL,
    published_at     timestamptz
);

CREATE INDEX idx_outbox_status_next_attempt ON platform.outbox_events (status, next_attempt_at);
CREATE INDEX idx_outbox_event ON platform.outbox_events (event_id);
