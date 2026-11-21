package com.platform.ingestion.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class DeviceDtos {

    private DeviceDtos() {}

    public record CreateDeviceRequest(
            @NotBlank
                    @Pattern(regexp = "^[A-Za-z0-9._:-]{1,64}$", message = "must be 1-64 characters of [A-Za-z0-9._:-]")
                    String deviceId,
            @Size(max = 255) String description) {}

    public record DeviceResponse(
            String deviceId, String description, String status, Instant createdAt, String clientId) {

        static DeviceResponse from(Device d) {
            return new DeviceResponse(
                    d.getDeviceId(),
                    d.getDescription(),
                    d.getStatus().name(),
                    d.getCreatedAt(),
                    d.getCredentialClientId());
        }
    }

    /** Returned once at registration: the secret cannot be read back later, only replaced by rotating it. */
    public record RegisteredDeviceResponse(
            String deviceId,
            String description,
            String status,
            Instant createdAt,
            String clientId,
            String clientSecret) {}

    public record DeviceCredentialsResponse(String deviceId, String clientId, String clientSecret) {}
}
