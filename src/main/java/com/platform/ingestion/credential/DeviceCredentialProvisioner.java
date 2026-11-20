package com.platform.ingestion.credential;

/** Manages the identity-provider side of a device: one confidential client per device. */
public interface DeviceCredentialProvisioner {

    /** Creates the client for a device; fails with reason CONFLICT when it already exists. */
    DeviceCredential create(String tenantCode, String deviceId);

    /** Issues a new secret for an existing client; the previous secret stops working. Returns the new secret. */
    String rotateSecret(String clientId);

    /** Stops the client from obtaining tokens. Idempotent; a missing client is not an error. */
    void disable(String clientId);

    /** Removes the client. Idempotent; a missing client is not an error. */
    void delete(String clientId);
}
