package com.platform.ingestion.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.oas.OpenApi30;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.common.FieldError;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

/** Compiled schemas are cached per (tenant, contract, version); versions are immutable so entries never go stale. */
@Service
public class SchemaValidationService {

    private static final int MAX_CACHE_ENTRIES = 500;

    public record CacheKey(UUID tenantId, UUID contractId, int version) {}

    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4, b -> b.metaSchema(OpenApi30.getInstance())
                    .defaultMetaSchemaIri(OpenApi30.getInstance().getIri())
                    .schemaLoaders(loaders -> loaders.values(list -> {
                        list.clear();
                        list.add(new LocalOnlySchemaLoader());
                    })));

    private final SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
            .formatAssertionsEnabled(true)
            .typeLoose(false)
            .pathType(PathType.JSON_PATH)
            .build();

    private final ConcurrentMap<CacheKey, JsonSchema> cache = new ConcurrentHashMap<>();

    public List<FieldError> validate(CacheKey key, JsonNode schemaDocument, JsonNode payload) {
        JsonSchema schema = cache.get(key);
        if (schema == null) {
            schema = compile(schemaDocument);
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.clear();
            }
            cache.put(key, schema);
        }
        return ValidationMessageMapper.map(schema.validate(payload));
    }

    public List<FieldError> validate(JsonNode schemaDocument, JsonNode payload) {
        return ValidationMessageMapper.map(compile(schemaDocument).validate(payload));
    }

    /** Fails with INVALID_CONTRACT when the extracted schema cannot be compiled. */
    public void assertCompilable(JsonNode schemaDocument) {
        try {
            compile(schemaDocument);
        } catch (RuntimeException e) {
            throw new ApiException(
                    ErrorCode.INVALID_CONTRACT,
                    "Request schema is not a valid OpenAPI 3.0 schema: "
                            + e.getClass().getSimpleName());
        }
    }

    public int cacheSize() {
        return cache.size();
    }

    private JsonSchema compile(JsonNode schemaDocument) {
        JsonSchema schema = factory.getSchema(schemaDocument, config);
        schema.initializeValidators();
        return schema;
    }
}
