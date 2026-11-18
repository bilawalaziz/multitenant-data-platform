package com.platform.ingestion.ingestion;

import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.ingestion.IngestionDtos.IngestionDetail;
import com.platform.ingestion.ingestion.IngestionDtos.IngestionSummary;
import com.platform.ingestion.persistence.IngestionRecord;
import com.platform.ingestion.persistence.IngestionRecordRepository;
import com.platform.ingestion.tenant.TenantService;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of ingestion records. Every query is filtered by the tenant resolved from the path. */
@Service
public class IngestionQueryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final IngestionRecordRepository records;
    private final TenantService tenantService;

    public IngestionQueryService(IngestionRecordRepository records, TenantService tenantService) {
        this.records = records;
        this.tenantService = tenantService;
    }

    @Transactional(readOnly = true)
    public List<IngestionSummary> list(String tenantCode, Integer limit) {
        UUID tenantId = tenantService.requireContext(tenantCode).id();
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        return records.findByTenantIdOrderByReceivedAtDesc(tenantId, PageRequest.of(0, size)).stream()
                .map(r -> new IngestionSummary(
                        r.getId(), r.getEventId(), r.getDeviceId(), r.getStatus(), r.getReceivedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public IngestionDetail detail(String tenantCode, UUID ingestionId) {
        UUID tenantId = tenantService.requireContext(tenantCode).id();
        IngestionRecord r = records.findByIdAndTenantId(ingestionId, tenantId)
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
