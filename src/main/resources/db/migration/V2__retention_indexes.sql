-- Support the retention job: each predicate below is range-scanned per batch instead of scanning the table.

-- Only PUBLISHED rows are purged, so the index stays small while PENDING/FAILED rows are never in it.
CREATE INDEX idx_outbox_published_at ON platform.outbox_events (published_at) WHERE status = 'PUBLISHED';

CREATE INDEX idx_idempotency_created_at ON platform.idempotency_records (created_at);

CREATE INDEX idx_ingestion_records_received_at ON platform.ingestion_records (received_at);

-- Used by the "not referenced" check when purging ingestion records (and by the foreign key itself).
CREATE INDEX idx_idempotency_ingestion_id ON platform.idempotency_records (ingestion_id);
