package com.platform.ingestion.tenant;

import java.util.UUID;

public record TenantContext(UUID id, String code, String schemaName, String kafkaTopic) {}
