package com.platform.ingestion.device;

import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.device.DeviceDtos.DeviceResponse;
import com.platform.ingestion.tenant.TenantService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceService {

    private final DeviceRepository devices;
    private final TenantService tenantService;
    private final Clock clock;

    public DeviceService(DeviceRepository devices, TenantService tenantService, Clock clock) {
        this.devices = devices;
        this.tenantService = tenantService;
        this.clock = clock;
    }

    @Transactional
    public DeviceResponse register(String tenantCode, String deviceId, String description) {
        UUID tenantId = tenantService.requireContext(tenantCode).id();
        if (devices.existsByTenantIdAndDeviceId(tenantId, deviceId)) {
            throw new ApiException(ErrorCode.CONFLICT, "Device '" + deviceId + "' is already registered");
        }
        Device device = devices.saveAndFlush(
                new Device(UUID.randomUUID(), tenantId, deviceId, description, DeviceStatus.ACTIVE, clock.instant()));
        return DeviceResponse.from(device);
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> list(String tenantCode) {
        UUID tenantId = tenantService.requireContext(tenantCode).id();
        return devices.findByTenantIdOrderByDeviceIdAsc(tenantId).stream()
                .map(DeviceResponse::from)
                .toList();
    }

    /** Used by the ingestion pipeline: the canonical device must be a registered ACTIVE device of the tenant. */
    @Transactional(readOnly = true)
    public void requireActiveDevice(UUID tenantId, String deviceId) {
        boolean active = devices.findByTenantIdAndDeviceId(tenantId, deviceId)
                .map(d -> d.getStatus() == DeviceStatus.ACTIVE)
                .orElse(false);
        if (!active) {
            throw new ApiException(
                    ErrorCode.DEVICE_NOT_REGISTERED,
                    "Device '" + deviceId + "' is not registered as an active device for this tenant");
        }
    }
}
