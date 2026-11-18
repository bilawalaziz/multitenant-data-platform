package com.platform.ingestion.transformation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** Result of applying a mapping: the canonical fields derived from a tenant payload. */
public record MappedFields(
        String deviceId,
        String sensorId,
        BigDecimal value,
        String unit,
        Instant eventTimestamp,
        Map<String, Object> metadata) {}
