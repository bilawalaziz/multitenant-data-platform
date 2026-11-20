package com.platform.ingestion.messaging;

import java.util.concurrent.CompletableFuture;

/** Port for publishing canonical events. */
public interface EventPublisher {

    /**
     * Hands the message to the broker without waiting for the acknowledgement.
     *
     * @return a future that completes when the broker acknowledged the record, or completes exceptionally with a
     * {@link PublishException} when it did not. Implementations may also throw a runtime exception directly when
     * the failure is known immediately; callers treat that like a failed future.
     */
    CompletableFuture<Void> publish(OutboxMessage message);
}
