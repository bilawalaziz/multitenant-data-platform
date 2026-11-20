-- Identity-provider client that authenticates a device; null for devices registered before per-device
-- credentials existed (they can still ingest through a tenant-wide client until that is switched off).
ALTER TABLE platform.devices ADD COLUMN credential_client_id varchar(255);

CREATE UNIQUE INDEX uq_devices_credential_client
    ON platform.devices (credential_client_id)
    WHERE credential_client_id IS NOT NULL;
