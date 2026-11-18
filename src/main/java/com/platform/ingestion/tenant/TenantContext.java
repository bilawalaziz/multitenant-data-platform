package com.platform.ingestion.tenant;

import java.util.UUID;

/** Trusted, resolved tenant identity handed to services. Never built from request data directly. */
public record TenantContext(UUID id, String code, String schemaName, String kafkaTopic) {}
