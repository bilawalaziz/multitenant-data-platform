package com.platform.ingestion.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "platform.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1000") long fixedDelayMs,
        @DefaultValue("50") int batchSize,
        @DefaultValue("50") int maxAttempts,
        @DefaultValue("1000") long backoffBaseMs,
        @DefaultValue("30000") long backoffCapMs,
        @DefaultValue("30s") Duration lease) {}
