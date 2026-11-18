package com.platform.ingestion.contract;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractMappingRepository extends JpaRepository<ContractMapping, UUID> {

    Optional<ContractMapping> findByContractVersionIdAndTenantId(UUID contractVersionId, UUID tenantId);
}
