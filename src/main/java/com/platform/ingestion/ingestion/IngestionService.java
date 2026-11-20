package com.platform.ingestion.ingestion;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.common.FieldError;
import com.platform.ingestion.contract.ContractResolver;
import com.platform.ingestion.contract.ContractResolver.ActiveContract;
import com.platform.ingestion.device.DeviceService;
import com.platform.ingestion.ingestion.IngestionDtos.IngestionResponse;
import com.platform.ingestion.ingestion.IngestionDtos.IngestionResult;
import com.platform.ingestion.ingestion.IngestionWriter.WriteOutcome;
import com.platform.ingestion.ingestion.IngestionWriter.WriteRequest;
import com.platform.ingestion.observability.MdcKeys;
import com.platform.ingestion.observability.PlatformMetrics;
import com.platform.ingestion.ratelimit.RateLimiter;
import com.platform.ingestion.security.DeviceBindingPolicy;
import com.platform.ingestion.security.TenantResolver;
import com.platform.ingestion.tenant.TenantContext;
import com.platform.ingestion.tenant.TenantScope;
import com.platform.ingestion.transformation.MappedFields;
import com.platform.ingestion.transformation.MappingEngine;
import com.platform.ingestion.validation.SchemaValidationService;
import com.platform.ingestion.validation.SchemaValidationService.CacheKey;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final TenantResolver tenantResolver;
    private final IdempotencyService idempotency;
    private final ContractResolver contractResolver;
    private final SchemaValidationService validation;
    private final MappingEngine mappingEngine;
    private final DeviceService devices;
    private final DeviceBindingPolicy deviceBinding;
    private final IngestionWriter writer;
    private final RateLimiter rateLimiter;
    private final PlatformMetrics metrics;
    private final Clock clock;
    private final int maxPayloadBytes;

    private final ObjectReader payloadReader;

    public IngestionService(
            TenantResolver tenantResolver,
            IdempotencyService idempotency,
            ContractResolver contractResolver,
            SchemaValidationService validation,
            MappingEngine mappingEngine,
            DeviceService devices,
            DeviceBindingPolicy deviceBinding,
            IngestionWriter writer,
            RateLimiter rateLimiter,
            PlatformMetrics metrics,
            Clock clock,
            ObjectMapper objectMapper,
            @Value("${platform.ingestion.max-payload-bytes:65536}") int maxPayloadBytes) {
        this.tenantResolver = tenantResolver;
        this.idempotency = idempotency;
        this.contractResolver = contractResolver;
        this.validation = validation;
        this.mappingEngine = mappingEngine;
        this.devices = devices;
        this.deviceBinding = deviceBinding;
        this.writer = writer;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.clock = clock;
        this.maxPayloadBytes = maxPayloadBytes;
        this.payloadReader = objectMapper
                .readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public int maxPayloadBytes() {
        return maxPayloadBytes;
    }

    public IngestionResult ingest(
            Authentication authentication, String tenantHint, String idempotencyKey, byte[] body) {
        Timer.Sample sample = metrics.startTimer();
        try {
            IngestionResult result = process(authentication, tenantHint, idempotencyKey, body);
            if (result.duplicate()) {
                metrics.requestDuplicate();
            } else {
                metrics.requestAccepted();
            }
            return result;
        } catch (ApiException e) {
            metrics.requestRejected(e.errorCode().name());
            if (e.errorCode() == ErrorCode.PAYLOAD_VALIDATION_FAILED) {
                metrics.validationFailed();
            }
            throw e;
        } catch (RuntimeException e) {
            metrics.requestRejected(ErrorCode.INTERNAL_ERROR.name());
            throw e;
        } finally {
            metrics.stopTimer(sample);
            MDC.remove(MdcKeys.TENANT_ID);
            MDC.remove(MdcKeys.INGESTION_ID);
            MDC.remove(MdcKeys.EVENT_ID);
        }
    }

    private IngestionResult process(
            Authentication authentication, String tenantHint, String idempotencyKey, byte[] body) {
        // Both limits use only the token's claims, so a rejected request costs no database query and a flooding
        // tenant or device cannot exhaust the connection pool.
        String tenantCode = tenantResolver.claimedTenantCode(authentication, tenantHint);
        MDC.put(MdcKeys.TENANT_ID, tenantCode);
        rateLimiter.checkTenant(tenantCode);
        Optional<String> boundDevice = deviceBinding.boundDevice(authentication);
        // A device credential names its device up front; a tenant-wide one is limited once the payload is mapped.
        if (boundDevice.isPresent()) {
            rateLimiter.checkDevice(tenantCode, boundDevice.get());
        }
        TenantContext tenant = tenantResolver.resolve(authentication, tenantHint);
        // Opened before the first tenant-owned query and before the writer's transaction takes its connection.
        try (TenantScope ignored = TenantScope.tenant(tenant.id())) {
            return processInScope(tenant, boundDevice, idempotencyKey, body);
        }
    }

    private IngestionResult processInScope(
            TenantContext tenant, Optional<String> boundDevice, String idempotencyKey, byte[] body) {
        String key = idempotency.requireValidKey(idempotencyKey);
        if (body == null || body.length == 0) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "Request body is empty");
        }
        if (body.length > maxPayloadBytes) {
            throw new ApiException(
                    ErrorCode.MALFORMED_REQUEST,
                    "Payload exceeds the maximum size of " + (maxPayloadBytes / 1024) + " KB",
                    List.of(),
                    HttpStatus.PAYLOAD_TOO_LARGE);
        }
        JsonNode payload = parse(body);
        String hash = idempotency.hash(payload);

        Optional<UUID> replay = idempotency.findReplay(tenant.id(), key, hash);
        if (replay.isPresent()) {
            return duplicate(tenant, boundDevice, replay.get());
        }

        ActiveContract contract = contractResolver.resolveActive(tenant);
        List<FieldError> errors = validation.validate(
                new CacheKey(tenant.id(), contract.contractId(), contract.version()),
                contract.requestSchema(),
                payload);
        if (!errors.isEmpty()) {
            throw new ApiException(
                    ErrorCode.PAYLOAD_VALIDATION_FAILED, "Payload does not conform to the active contract", errors);
        }

        MappedFields fields = mappingEngine.map(payload, contract.mapping());
        if (boundDevice.isEmpty()) {
            rateLimiter.checkDevice(tenant.code(), fields.deviceId());
        }
        DeviceBindingPolicy.requireSameDevice(boundDevice, fields.deviceId());
        devices.requireActiveDevice(tenant.id(), fields.deviceId());

        UUID ingestionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        MDC.put(MdcKeys.INGESTION_ID, ingestionId.toString());
        MDC.put(MdcKeys.EVENT_ID, eventId.toString());
        Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        ObjectNode canonical = CanonicalEvent.build(
                eventId, ingestionId, tenant.code(), contract.contractId(), contract.version(), fields, receivedAt);

        WriteOutcome outcome = writer.write(new WriteRequest(
                tenant,
                key,
                hash,
                ingestionId,
                eventId,
                fields.deviceId(),
                contract.versionId(),
                payload,
                canonical,
                receivedAt));
        if (outcome.duplicate()) {
            return duplicate(tenant, boundDevice, outcome.ingestionId());
        }
        log.info("Accepted reading for device {} (contract v{})", fields.deviceId(), contract.version());
        return new IngestionResult(
                false,
                new IngestionResponse(
                        "ACCEPTED",
                        ingestionId,
                        eventId,
                        tenant.code(),
                        contract.contractId(),
                        contract.version(),
                        receivedAt));
    }

    private IngestionResult duplicate(TenantContext tenant, Optional<String> boundDevice, UUID originalIngestionId) {
        return new IngestionResult(true, writer.replayResponse(tenant.id(), originalIngestionId, boundDevice));
    }

    private JsonNode parse(byte[] body) {
        try {
            JsonNode node = payloadReader.readTree(body);
            if (node == null || node.isMissingNode()) {
                throw new ApiException(ErrorCode.MALFORMED_REQUEST, "Request body is empty");
            }
            return node;
        } catch (IOException e) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "Request body is not valid JSON");
        }
    }
}
