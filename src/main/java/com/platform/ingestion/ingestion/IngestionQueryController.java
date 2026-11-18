package com.platform.ingestion.ingestion;

import com.platform.ingestion.ingestion.IngestionDtos.IngestionDetail;
import com.platform.ingestion.ingestion.IngestionDtos.IngestionSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/ingestions")
public class IngestionQueryController {

    private final IngestionQueryService service;

    public IngestionQueryController(IngestionQueryService service) {
        this.service = service;
    }

    @GetMapping
    public List<IngestionSummary> list(@PathVariable String tenantId, @RequestParam(required = false) Integer limit) {
        return service.list(tenantId, limit);
    }

    @GetMapping("/{ingestionId}")
    public IngestionDetail detail(@PathVariable String tenantId, @PathVariable UUID ingestionId) {
        return service.detail(tenantId, ingestionId);
    }
}
