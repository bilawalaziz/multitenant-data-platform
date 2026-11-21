package com.platform.ingestion.ingestion;

import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.device.DeviceService;
import com.platform.ingestion.ingestion.IngestionDtos.IngestionDetail;
import com.platform.ingestion.ingestion.IngestionDtos.IngestionSummary;
import com.platform.ingestion.persistence.IngestionRecord;
import com.platform.ingestion.persistence.IngestionRecordRepository;
import com.platform.ingestion.security.DeviceBindingPolicy;
import com.platform.ingestion.security.Roles;
import com.platform.ingestion.tenant.TenantService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionQueryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final IngestionRecordRepository records;
    private final TenantService tenantService;
    private final DeviceService devices;
    private final DeviceBindingPolicy bindingPolicy;

    public IngestionQueryService(
            IngestionRecordRepository records,
            TenantService tenantService,
            DeviceService devices,
            DeviceBindingPolicy bindingPolicy) {
        this.records = records;
        this.tenantService = tenantService;
        this.devices = devices;
        this.bindingPolicy = bindingPolicy;
    }

    /**
     * The device a read is confined to: empty for admins and accepted tenant-wide tokens. A device token must still
     * belong to an active device, so a deactivated device cannot keep reading with a token that has not expired.
     */
    private Optional<String> readScope(Authentication auth, UUID tenantId) {
        boolean admin = auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .anyMatch(a -> a.equals("ROLE_" + Roles.PLATFORM_ADMIN) || a.equals("ROLE_" + Roles.TENANT_ADMIN));
        if (admin) {
            return Optional.empty();
        }
        Optional<String> device = bindingPolicy.boundDevice(auth);
        device.ifPresent(d -> devices.requireActiveDevice(tenantId, d));
        return device;
    }

    @Transactional(readOnly = true)
    public List<IngestionSummary> list(String tenantCode, Integer limit, Authentication auth) {
        UUID tenantId = tenantService.requireContext(tenantCode).id();
        Optional<String> scope = readScope(auth, tenantId);
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        PageRequest page = PageRequest.of(0, size);
        List<IngestionRecord> found = scope.isPresent()
                ? records.findByTenantIdAndDeviceIdOrderByReceivedAtDesc(tenantId, scope.get(), page)
                : records.findByTenantIdOrderByReceivedAtDesc(tenantId, page);
        return found.stream()
                .map(r -> new IngestionSummary(
                        r.getId(), r.getEventId(), r.getDeviceId(), r.getStatus(), r.getReceivedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public IngestionDetail detail(String tenantCode, UUID ingestionId, Authentication auth) {
        UUID tenantId = tenantService.requireContext(tenantCode).id();
        Optional<String> scope = readScope(auth, tenantId);
        // Another device's record is reported as missing so the id cannot be used to probe for existence.
        IngestionRecord r = (scope.isPresent()
                        ? records.findByIdAndTenantIdAndDeviceId(ingestionId, tenantId, scope.get())
                        : records.findByIdAndTenantId(ingestionId, tenantId))
                .orElseThrow(() -> ApiException.notFound("Ingestion"));
        return new IngestionDetail(
                r.getId(),
                r.getEventId(),
                r.getDeviceId(),
                r.getStatus(),
                r.getReceivedAt(),
                r.getIdempotencyKey(),
                r.getContractVersionId(),
                r.getCanonicalEvent(),
                r.getRawPayload());
    }
}
