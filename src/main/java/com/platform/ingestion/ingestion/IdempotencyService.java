package com.platform.ingestion.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.common.Hashing;
import com.platform.ingestion.common.JsonCanonicalizer;
import com.platform.ingestion.persistence.IdempotencyRecord;
import com.platform.ingestion.persistence.IdempotencyRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Idempotency per (tenant, Idempotency-Key). The request hash is SHA-256 of the canonical JSON of the payload,
 * so key order and number formatting do not matter, but any real change does.
 */
@Service
public class IdempotencyService {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final IdempotencyRepository repository;

    public IdempotencyService(IdempotencyRepository repository) {
        this.repository = repository;
    }

    public String requireValidKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(ErrorCode.MISSING_IDEMPOTENCY_KEY, "Header 'Idempotency-Key' is required");
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new ApiException(
                    ErrorCode.MALFORMED_REQUEST, "Idempotency-Key must be 1-128 characters of [A-Za-z0-9._:-]");
        }
        return key;
    }

    public String hash(JsonNode payload) {
        return Hashing.sha256Hex(JsonCanonicalizer.canonicalize(payload));
    }

    /**
     * Looks up an existing claim. Same hash returns the original ingestion id; a different hash is a conflict.
     */
    public Optional<UUID> findReplay(UUID tenantId, String key, String requestHash) {
        return repository
                .findById(new IdempotencyRecord.Key(tenantId, key))
                .map(existing -> decide(existing, requestHash));
    }

    /**
     * Claims the key atomically (INSERT ... ON CONFLICT DO NOTHING). Empty when this call won the claim;
     * the original ingestion id when an identical request already exists.
     */
    public Optional<UUID> claim(UUID tenantId, String key, String requestHash, UUID ingestionId, Instant now) {
        int inserted = repository.insertIfAbsent(tenantId, key, requestHash, ingestionId, now);
        if (inserted == 1) {
            return Optional.empty();
        }
        IdempotencyRecord existing = repository
                .findById(new IdempotencyRecord.Key(tenantId, key))
                .orElseThrow(
                        () -> new ApiException(ErrorCode.CONFLICT, "Idempotency claim is in flux; retry the request"));
        return Optional.of(decide(existing, requestHash));
    }

    private static UUID decide(IdempotencyRecord existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new ApiException(
                    ErrorCode.IDEMPOTENCY_KEY_CONFLICT, "Idempotency-Key was already used with a different payload");
        }
        return existing.getIngestionId();
    }
}
