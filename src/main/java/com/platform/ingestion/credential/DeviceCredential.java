package com.platform.ingestion.credential;

/** The identity a device authenticates with; the secret is only known at creation or rotation. */
public record DeviceCredential(String clientId, String clientSecret) {

    @Override
    public String toString() {
        // Keeps the secret out of logs if a credential is ever logged by accident.
        return "DeviceCredential[clientId=" + clientId + ", clientSecret=***]";
    }
}
