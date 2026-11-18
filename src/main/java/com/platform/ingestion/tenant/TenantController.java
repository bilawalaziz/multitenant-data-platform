package com.platform.ingestion.tenant;

import com.platform.ingestion.tenant.TenantDtos.CreateTenantRequest;
import com.platform.ingestion.tenant.TenantDtos.SettingsRequest;
import com.platform.ingestion.tenant.TenantDtos.SettingsResponse;
import com.platform.ingestion.tenant.TenantDtos.TenantResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping("/api/v1/admin/tenants")
    public ResponseEntity<TenantResponse> register(@Valid @RequestBody CreateTenantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(tenantService.register(request.tenantId(), request.displayName()));
    }

    @GetMapping("/api/v1/admin/tenants")
    public List<TenantResponse> list() {
        return tenantService.list();
    }

    @GetMapping("/api/v1/tenants/{tenantId}")
    public TenantResponse get(@PathVariable String tenantId) {
        return tenantService.get(tenantId);
    }

    @GetMapping("/api/v1/tenants/{tenantId}/settings")
    public SettingsResponse getSettings(@PathVariable String tenantId) {
        return tenantService.getSettings(tenantId);
    }

    @PutMapping("/api/v1/tenants/{tenantId}/settings")
    public SettingsResponse updateSettings(@PathVariable String tenantId, @Valid @RequestBody SettingsRequest request) {
        return tenantService.updateSettings(tenantId, request.settings());
    }
}
