package com.platform.ingestion.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims due rows. FOR UPDATE SKIP LOCKED lets several application instances publish concurrently
     * without claiming the same row twice. Callers must keep the claiming transaction short and push
     * next_attempt_at forward (lease) before committing.
     */
    @Query(
            value = "SELECT * FROM platform.outbox_events "
                    + "WHERE status = 'PENDING' AND next_attempt_at <= :now "
                    + "ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> claimDue(@Param("now") Instant now, @Param("limit") int limit);

    /** Marks rows published, only those still PENDING. @return rows updated. */
    @Modifying
    @Query("update OutboxEvent o set o.status = com.platform.ingestion.persistence.OutboxStatus.PUBLISHED, "
            + "o.publishedAt = :now, o.lastError = null "
            + "where o.id in :ids and o.status = com.platform.ingestion.persistence.OutboxStatus.PENDING")
    int markPublishedAll(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);

    /** Records a failed attempt, only if the row is still PENDING. @return rows updated (0 or 1). */
    @Modifying
    @Query("update OutboxEvent o set o.attempts = o.attempts + 1, o.lastError = :error, "
            + "o.nextAttemptAt = :next, o.status = :newStatus "
            + "where o.id = :id and o.status = com.platform.ingestion.persistence.OutboxStatus.PENDING")
    int recordFailedAttempt(
            @Param("id") UUID id,
            @Param("error") String error,
            @Param("next") Instant next,
            @Param("newStatus") OutboxStatus newStatus);

    /** Makes still-leased PENDING rows due again at {@code at} (rows that were claimed but not sent). */
    @Modifying
    @Query("update OutboxEvent o set o.nextAttemptAt = :at "
            + "where o.id in :ids and o.status = com.platform.ingestion.persistence.OutboxStatus.PENDING "
            + "and o.nextAttemptAt > :at")
    int releaseLease(@Param("ids") Collection<UUID> ids, @Param("at") Instant at);

    List<OutboxEvent> findByStatusOrderByCreatedAtDesc(OutboxStatus status, Pageable pageable);

    List<OutboxEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(OutboxStatus status);

    long countByTenantId(UUID tenantId);

    @Query("select min(o.createdAt) from OutboxEvent o "
            + "where o.status = com.platform.ingestion.persistence.OutboxStatus.PENDING")
    Instant oldestPendingCreatedAt();
}
