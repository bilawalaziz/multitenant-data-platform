package com.platform.ingestion.contract;

import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.contract.ContractDtos.ContractDetailResponse;
import com.platform.ingestion.contract.ContractDtos.ContractSummaryResponse;
import com.platform.ingestion.contract.ContractDtos.ContractVersionResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/contracts")
public class ContractController {

    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ContractVersionResponse> upload(
            @PathVariable String tenantId,
            @RequestParam("name") String name,
            @RequestPart("spec") MultipartFile spec,
            @RequestPart("mapping") MultipartFile mapping,
            @RequestParam(value = "ingestionPath", required = false) String ingestionPath) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contractService.upload(tenantId, name, bytes(spec), bytes(mapping), ingestionPath));
    }

    @GetMapping
    public List<ContractSummaryResponse> list(@PathVariable String tenantId) {
        return contractService.list(tenantId);
    }

    @GetMapping("/active")
    public ContractVersionResponse active(@PathVariable String tenantId) {
        return contractService.active(tenantId);
    }

    @GetMapping("/{contractId}")
    public ContractDetailResponse detail(@PathVariable String tenantId, @PathVariable UUID contractId) {
        return contractService.detail(tenantId, contractId);
    }

    @GetMapping("/{contractId}/versions/{version}")
    public ContractVersionResponse version(
            @PathVariable String tenantId, @PathVariable UUID contractId, @PathVariable int version) {
        return contractService.versionDetail(tenantId, contractId, version);
    }

    @PostMapping("/{contractId}/versions/{version}/activate")
    public ContractVersionResponse activate(
            @PathVariable String tenantId, @PathVariable UUID contractId, @PathVariable int version) {
        return contractService.activate(tenantId, contractId, version);
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ApiException(
                    ErrorCode.MALFORMED_REQUEST, "Uploaded part '" + file.getName() + "' could not be read");
        }
    }
}
