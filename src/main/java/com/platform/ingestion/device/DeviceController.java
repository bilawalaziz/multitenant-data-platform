package com.platform.ingestion.device;

import com.platform.ingestion.device.DeviceDtos.CreateDeviceRequest;
import com.platform.ingestion.device.DeviceDtos.DeviceResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PostMapping
    public ResponseEntity<DeviceResponse> register(
            @PathVariable String tenantId, @Valid @RequestBody CreateDeviceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(deviceService.register(tenantId, request.deviceId(), request.description()));
    }

    @GetMapping
    public List<DeviceResponse> list(@PathVariable String tenantId) {
        return deviceService.list(tenantId);
    }
}
