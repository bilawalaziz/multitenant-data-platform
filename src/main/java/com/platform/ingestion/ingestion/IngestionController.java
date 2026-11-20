package com.platform.ingestion.ingestion;

import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.ingestion.IngestionDtos.IngestionResponse;
import com.platform.ingestion.ingestion.IngestionDtos.IngestionResult;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ingestion")
public class IngestionController {

    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/readings")
    public ResponseEntity<IngestionResponse> ingest(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHint,
            HttpServletRequest request) {
        int limit = ingestionService.maxPayloadBytes();
        IngestionResult result =
                ingestionService.ingest(authentication, tenantHint, idempotencyKey, readBounded(request, limit));
        if (result.duplicate()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.response());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result.response());
    }

    private static byte[] readBounded(HttpServletRequest request, int limit) {
        try {
            // Read one byte more than allowed so oversize bodies are detected without buffering them fully.
            return request.getInputStream().readNBytes(limit + 1);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "Request body could not be read");
        }
    }
}
