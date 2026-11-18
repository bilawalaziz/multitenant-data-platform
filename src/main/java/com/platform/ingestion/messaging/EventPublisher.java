package com.platform.ingestion.messaging;

/** Port for publishing canonical events. Implementations must block until the broker acknowledged the record. */
public interface EventPublisher {

    /** @throws PublishException when the record was not acknowledged. */
    void publish(OutboxMessage message);
}
