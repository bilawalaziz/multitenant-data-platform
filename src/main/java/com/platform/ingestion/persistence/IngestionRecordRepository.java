package com.platform.ingestion.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionRecordRepository extends JpaRepository<IngestionRecord, UUID> {

    Optional<IngestionRecord> findByIdAndTenantId(UUID id, UUID tenantId);

    List<IngestionRecord> findByTenantIdOrderByReceivedAtDesc(UUID tenantId, Pageable pageable);

    long countByTenantId(UUID tenantId);
}
