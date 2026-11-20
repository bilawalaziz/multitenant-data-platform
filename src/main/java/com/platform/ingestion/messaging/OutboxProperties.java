package com.platform.ingestion.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param issueDeadline how long one cycle may keep issuing sends; must leave room for the send timeout inside the
 * lease (checked by {@link OutboxPublisher}).
 */
@ConfigurationProperties(prefix = "platform.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1000") long fixedDelayMs,
        @DefaultValue("50") int batchSize,
        @DefaultValue("50") int maxAttempts,
        @DefaultValue("1000") long backoffBaseMs,
        @DefaultValue("30000") long backoffCapMs,
        @DefaultValue("30s") Duration lease,
        @DefaultValue("10s") Duration issueDeadline) {}
