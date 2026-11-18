package com.platform.ingestion.contract;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ContractRepository extends JpaRepository<Contract, UUID> {

    Optional<Contract> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Contract> findByTenantIdOrderByNameAsc(UUID tenantId);

    /** Row lock serialises concurrent uploads of the same contract name so version numbers stay gapless. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Contract> findWithLockByTenantIdAndName(UUID tenantId, String name);
}
