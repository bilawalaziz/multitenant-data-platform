package com.platform.ingestion.messaging;

/** Raised when an event could not be handed to the broker (broker down, timeout, interruption). */
public class PublishException extends RuntimeException {

    public PublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
