package com.platform.ingestion.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, IdempotencyRecord.Key> {

    /** Returns 1 when claimed, 0 when it existed; a concurrent insert of the same key blocks until the first commits. */
    @Modifying
    @Query(
            value = "INSERT INTO platform.idempotency_records "
                    + "(tenant_id, idempotency_key, request_hash, ingestion_id, created_at) "
                    + "VALUES (:tenantId, :key, :hash, :ingestionId, :createdAt) "
                    + "ON CONFLICT (tenant_id, idempotency_key) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(
            @Param("tenantId") UUID tenantId,
            @Param("key") String key,
            @Param("hash") String hash,
            @Param("ingestionId") UUID ingestionId,
            @Param("createdAt") Instant createdAt);
}
