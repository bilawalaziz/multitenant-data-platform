package com.platform.ingestion.contract;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContractVersionRepository extends JpaRepository<ContractVersion, UUID> {

    List<ContractVersion> findByContractIdAndTenantIdOrderByVersionDesc(UUID contractId, UUID tenantId);

    List<ContractVersion> findByTenantId(UUID tenantId);

    Optional<ContractVersion> findByContractIdAndTenantIdAndVersion(UUID contractId, UUID tenantId, int version);

    Optional<ContractVersion> findByTenantIdAndStatus(UUID tenantId, ContractStatus status);

    @Query("select coalesce(max(v.version), 0) from ContractVersion v where v.contractId = :contractId")
    int maxVersion(@Param("contractId") UUID contractId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ContractVersion v set v.status = com.platform.ingestion.contract.ContractStatus.INACTIVE "
            + "where v.tenantId = :tenantId and v.status = com.platform.ingestion.contract.ContractStatus.ACTIVE")
    int deactivateActive(@Param("tenantId") UUID tenantId);
}
