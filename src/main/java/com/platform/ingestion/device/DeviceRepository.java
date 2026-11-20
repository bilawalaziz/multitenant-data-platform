package com.platform.ingestion.device;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    List<Device> findByTenantIdOrderByDeviceIdAsc(UUID tenantId);

    Optional<Device> findByTenantIdAndDeviceId(UUID tenantId, String deviceId);

    boolean existsByTenantIdAndDeviceId(UUID tenantId, String deviceId);

    boolean existsByCredentialClientId(String credentialClientId);
}
