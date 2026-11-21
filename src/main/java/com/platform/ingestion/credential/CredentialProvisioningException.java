package com.platform.ingestion.credential;

public class CredentialProvisioningException extends RuntimeException {

    public enum Reason {
        /** The identity provider could not be reached, timed out or rejected the call. */
        UNAVAILABLE,
        /** A client for this device already exists. */
        CONFLICT,
        /** The client to rotate does not exist. */
        NOT_FOUND
    }

    private final Reason reason;

    public CredentialProvisioningException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public CredentialProvisioningException(Reason reason, String message) {
        this(reason, message, null);
    }

    public Reason reason() {
        return reason;
    }
}
