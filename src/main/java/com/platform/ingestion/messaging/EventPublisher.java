package com.platform.ingestion.messaging;

import java.util.concurrent.CompletableFuture;

public interface EventPublisher {

    /**
     * Sends without waiting for the ack. The future fails with {@link PublishException}; an immediate runtime
     * exception is treated the same way.
     */
    CompletableFuture<Void> publish(OutboxMessage message);
}
