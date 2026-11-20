package com.platform.ingestion.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.persistence.IdempotencyRecord;
import com.platform.ingestion.persistence.IdempotencyRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdempotencyServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final IdempotencyRepository repository = mock(IdempotencyRepository.class);
    private final IdempotencyService service = new IdempotencyService(repository);
    private final UUID tenant = UUID.randomUUID();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private IdempotencyRecord existing(String hash, UUID ingestionId) {
        IdempotencyRecord record = mock(IdempotencyRecord.class);
        when(record.getRequestHash()).thenReturn(hash);
        when(record.getIngestionId()).thenReturn(ingestionId);
        return record;
    }

    @Test
    void hashIsCanonicalSensitiveToContentAndRejectsNonFiniteNumbers() {
        assertThat(service.hash(json("{\"a\":1,\"b\":{\"x\":[1,2],\"y\":\"z\"}}")))
                .hasSize(64)
                .isEqualTo(service.hash(json("{ \"b\" : {\"y\":\"z\",\"x\":[1,2]}, \"a\": 1.0 }")));

        String base = service.hash(json("{\"a\":1,\"l\":[1,2]}"));
        assertThat(service.hash(json("{\"a\":2,\"l\":[1,2]}"))).isNotEqualTo(base);
        assertThat(service.hash(json("{\"a\":1,\"l\":[2,1]}"))).isNotEqualTo(base);
        assertThat(service.hash(json("{\"a\":\"1\",\"l\":[1,2]}"))).isNotEqualTo(base);

        assertThatThrownBy(() -> service.hash(json("{\"temperature\":1e999}")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.MALFORMED_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("Numeric values must be finite");
                });
    }

    @Test
    void claimOutcomes() {
        when(repository.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(1);
        assertThat(service.claim(tenant, "k1", "hash", UUID.randomUUID(), Instant.now()))
                .isEmpty();

        UUID original = UUID.randomUUID();
        when(repository.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(0);
        IdempotencyRecord record = existing("hash", original);
        when(repository.findById(new IdempotencyRecord.Key(tenant, "k1"))).thenReturn(Optional.of(record));
        assertThat(service.claim(tenant, "k1", "hash", UUID.randomUUID(), Instant.now()))
                .contains(original);

        assertThatThrownBy(() -> service.claim(tenant, "k1", "other-hash", UUID.randomUUID(), Instant.now()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                        .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));
    }

    @Test
    void keyValidation() {
        assertThatThrownBy(() -> service.requireValidKey(null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                        .isEqualTo(ErrorCode.MISSING_IDEMPOTENCY_KEY));
        assertThatThrownBy(() -> service.requireValidKey("  ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.requireValidKey("has space"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MALFORMED_REQUEST));
        assertThatThrownBy(() -> service.requireValidKey("x".repeat(129))).isInstanceOf(ApiException.class);
        assertThat(service.requireValidKey("abc-123_X.y:z")).isEqualTo("abc-123_X.y:z");
        assertThat(service.requireValidKey("x".repeat(128))).hasSize(128);
    }
}
