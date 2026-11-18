package com.platform.ingestion.messaging;

import com.platform.ingestion.messaging.OutboxAdminService.OutboxEventResponse;
import com.platform.ingestion.messaging.OutboxAdminService.OutboxStats;
import com.platform.ingestion.persistence.OutboxStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/outbox")
public class OutboxAdminController {

    private final OutboxAdminService service;

    public OutboxAdminController(OutboxAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<OutboxEventResponse> list(
            @RequestParam(required = false) OutboxStatus status, @RequestParam(defaultValue = "100") int limit) {
        return service.list(status, limit);
    }

    @GetMapping("/stats")
    public OutboxStats stats() {
        return service.stats();
    }

    @PostMapping("/{id}/requeue")
    public OutboxEventResponse requeue(@PathVariable UUID id) {
        return service.requeue(id);
    }
}
