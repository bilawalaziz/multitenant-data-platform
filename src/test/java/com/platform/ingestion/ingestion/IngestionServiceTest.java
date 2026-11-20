package com.platform.ingestion.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.ingestion.TestJwts;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.contract.ContractResolver;
import com.platform.ingestion.contract.ContractResolver.ActiveContract;
import com.platform.ingestion.device.DeviceService;
import com.platform.ingestion.ingestion.IngestionDtos.IngestionResponse;
import com.platform.ingestion.ingestion.IngestionDtos.IngestionResult;
import com.platform.ingestion.ingestion.IngestionWriter.WriteOutcome;
import com.platform.ingestion.ingestion.IngestionWriter.WriteRequest;
import com.platform.ingestion.observability.PlatformMetrics;
import com.platform.ingestion.ratelimit.RateLimiter;
import com.platform.ingestion.security.DeviceBindingPolicy;
import com.platform.ingestion.security.Roles;
import com.platform.ingestion.security.TenantResolver;
import com.platform.ingestion.tenant.TenantContext;
import com.platform.ingestion.transformation.MappedFields;
import com.platform.ingestion.transformation.MappingDocument;
import com.platform.ingestion.transformation.MappingEngine;
import com.platform.ingestion.validation.SchemaValidationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class IngestionServiceTest {

    private final TenantResolver tenantResolver = mock(TenantResolver.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final ContractResolver contractResolver = mock(ContractResolver.class);
    private final SchemaValidationService validation = mock(SchemaValidationService.class);
    private final MappingEngine mappingEngine = mock(MappingEngine.class);
    private final DeviceService devices = mock(DeviceService.class);
    private final IngestionWriter writer = mock(IngestionWriter.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PlatformMetrics metrics = new PlatformMetrics(registry);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-17T14:30:01.123456Z"), ZoneOffset.UTC);

    private final TenantContext tenant =
            new TenantContext(UUID.randomUUID(), "tenant-alpha", "ts_tenant_alpha", "tenant-alpha.readings");
    private final Authentication auth = TestJwts.auth("tenant-alpha", Roles.TENANT_INGEST);
    private final UUID contractId = UUID.randomUUID();
    private final ActiveContract contract = new ActiveContract(
            contractId,
            UUID.randomUUID(),
            3,
            "alpha-readings",
            new ObjectMapper().createObjectNode(),
            new MappingDocument(Map.of()));
    private final MappedFields fields = new MappedFields(
            "MACHINE-001",
            "TEMP-001",
            new BigDecimal("92.5"),
            "CELSIUS",
            Instant.parse("2026-11-17T14:30:00Z"),
            Map.of("site", "plant-1"));

    private IngestionService service;

    @BeforeEach
    void setUp() {
        service = new IngestionService(
                tenantResolver,
                idempotency,
                contractResolver,
                validation,
                mappingEngine,
                devices,
                new DeviceBindingPolicy(true),
                writer,
                rateLimiter,
                metrics,
                clock,
                new ObjectMapper(),
                1024);
        when(tenantResolver.resolve(any(), any())).thenReturn(tenant);
        when(idempotency.requireValidKey(any())).thenAnswer(i -> i.getArgument(0));
        when(idempotency.hash(any())).thenReturn("hash");
        when(idempotency.findReplay(any(), any(), any())).thenReturn(Optional.empty());
        when(contractResolver.resolveActive(tenant)).thenReturn(contract);
        when(validation.validate(any(SchemaValidationService.CacheKey.class), any(), any()))
                .thenReturn(List.of());
        when(mappingEngine.map(any(), any())).thenReturn(fields);
        when(writer.write(any()))
                .thenAnswer(i ->
                        new WriteOutcome(false, i.<WriteRequest>getArgument(0).ingestionId()));
    }

    private double count(String name, String tagKey, String tagValue) {
        var counter = registry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0 : counter.count();
    }

    private static byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void happyPathValidatesTransformsChecksDeviceAndWritesOnce() {
        IngestionResult result = service.ingest(auth, null, "key-1", body("{\"a\":1}"));

        assertThat(result.duplicate()).isFalse();
        IngestionResponse response = result.response();
        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.tenantId()).isEqualTo("tenant-alpha");
        assertThat(response.contractId()).isEqualTo(contractId);
        assertThat(response.contractVersion()).isEqualTo(3);
        assertThat(response.receivedAt()).isEqualTo(Instant.parse("2026-11-17T14:30:01.123Z"));

        var order = inOrder(validation, mappingEngine, devices, writer);
        order.verify(validation).validate(any(SchemaValidationService.CacheKey.class), any(), any());
        order.verify(mappingEngine).map(any(), eq(contract.mapping()));
        order.verify(devices).requireActiveDevice(tenant.id(), "MACHINE-001");
        order.verify(writer).write(any());

        ArgumentCaptor<WriteRequest> captor = ArgumentCaptor.forClass(WriteRequest.class);
        verify(writer).write(captor.capture());
        JsonNode event = captor.getValue().canonicalEvent();
        assertThat(event.get("tenant_id").asText()).isEqualTo("tenant-alpha");
        assertThat(event.get("device_id").asText()).isEqualTo("MACHINE-001");
        assertThat(event.get("event_id").asText()).isEqualTo(response.eventId().toString());
        assertThat(event.get("contract_version").asInt()).isEqualTo(3);
        assertThat(count("ingestion.requests", "outcome", "accepted")).isEqualTo(1);
    }

    @Test
    void raceLostInsideTransactionAlsoYieldsDuplicate() {
        UUID original = UUID.randomUUID();
        doReturn(new WriteOutcome(true, original)).when(writer).write(any());
        when(writer.replayResponse(tenant.id(), original, Optional.empty()))
                .thenReturn(new IngestionResponse(
                        "DUPLICATE", original, UUID.randomUUID(), "tenant-alpha", contractId, 3, Instant.now()));

        assertThat(service.ingest(auth, null, "key-1", body("{}")).duplicate()).isTrue();
    }

    @Test
    void deviceTokenForAnotherDeviceIsRejectedBeforeRegistryAndWrite() {
        Authentication otherDevice = new JwtAuthenticationToken(
                TestJwts.jwt("device-client", "tenant-alpha", "MACHINE-999", List.of("tenant-ingest")));

        assertThatThrownBy(() -> service.ingest(otherDevice, null, "key-1", body("{}")))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DEVICE_MISMATCH));
        verify(devices, never()).requireActiveDevice(any(), any());
        verify(writer, never()).write(any());
        assertThat(count("ingestion.rejections", "reason", "DEVICE_MISMATCH")).isEqualTo(1);
    }

    @Test
    void replayIsCheckedAgainstTheDeviceOfTheToken() {
        UUID original = UUID.randomUUID();
        when(idempotency.findReplay(any(), any(), any())).thenReturn(Optional.of(original));
        when(writer.replayResponse(tenant.id(), original, Optional.of("MACHINE-999")))
                .thenThrow(new ApiException(ErrorCode.DEVICE_MISMATCH, "mismatch"));
        Authentication otherDevice = new JwtAuthenticationToken(
                TestJwts.jwt("device-client", "tenant-alpha", "MACHINE-999", List.of("tenant-ingest")));

        assertThatThrownBy(() -> service.ingest(otherDevice, null, "key-1", body("{}")))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DEVICE_MISMATCH));
    }

    @Test
    void unexpectedFailureIsCountedAsInternalErrorAndRethrown() {
        doThrow(new IllegalStateException("database down")).when(writer).write(any());

        assertThatThrownBy(() -> service.ingest(auth, null, "key-1", body("{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database down");
        assertThat(count("ingestion.requests", "outcome", "rejected")).isEqualTo(1);
        assertThat(count("ingestion.rejections", "reason", "INTERNAL_ERROR")).isEqualTo(1);
    }
}
