package com.platform.ingestion.device;

import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.credential.CredentialProvisioningException;
import com.platform.ingestion.credential.CredentialProvisioningException.Reason;
import com.platform.ingestion.credential.DeviceCredential;
import com.platform.ingestion.credential.DeviceCredentialProvisioner;
import com.platform.ingestion.device.DeviceDtos.DeviceCredentialsResponse;
import com.platform.ingestion.device.DeviceDtos.DeviceResponse;
import com.platform.ingestion.device.DeviceDtos.RegisteredDeviceResponse;
import com.platform.ingestion.tenant.TenantService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The identity-provider calls run outside database transactions so no connection is held while waiting on the
 * network. Registration creates the client first and compensates by deleting it if the device row cannot be
 * written, so a failure leaves neither a half-registered device nor an orphaned client.
 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceRepository devices;
    private final TenantService tenantService;
    private final DeviceCredentialProvisioner provisioner;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DeviceService(
            DeviceRepository devices,
            TenantService tenantService,
            DeviceCredentialProvisioner provisioner,
            TransactionTemplate tx,
            Clock clock) {
        this.devices = devices;
        this.tenantService = tenantService;
        this.provisioner = provisioner;
        this.tx = tx;
        this.clock = clock;
    }

    public RegisteredDeviceResponse register(String tenantCode, String deviceId, String description) {
        UUID tenantId = tenantService.requireContext(tenantCode).id();
        if (devices.existsByTenantIdAndDeviceId(tenantId, deviceId)) {
            throw conflict(deviceId);
        }
        DeviceCredential credential = provision(() -> provisioner.create(tenantCode, deviceId));
        Device saved;
        try {
            saved = tx.execute(status -> {
                Device device = new Device(
                        UUID.randomUUID(), tenantId, deviceId, description, DeviceStatus.ACTIVE, clock.instant());
                device.assignCredentialClient(credential.clientId());
                return devices.saveAndFlush(device);
            });
        } catch (RuntimeException e) {
            removeClient(credential.clientId());
            if (e instanceof DataIntegrityViolationException) {
                // A concurrent registration of the same device won the unique constraint.
                throw conflict(deviceId);
            }
            throw e;
        }
        return new RegisteredDeviceResponse(
                saved.getDeviceId(),
                saved.getDescription(),
                saved.getStatus().name(),
                saved.getCreatedAt(),
                credential.clientId(),
                credential.clientSecret());
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> list(String tenantCode) {
        UUID tenantId = tenantService.requireContext(tenantCode).id();
        return devices.findByTenantIdOrderByDeviceIdAsc(tenantId).stream()
                .map(DeviceResponse::from)
                .toList();
    }

    /** Creates the client for a device that has none, otherwise replaces the secret of the existing one. */
    public DeviceCredentialsResponse issueCredentials(String tenantCode, String deviceId) {
        UUID tenantId = tenantService.requireContext(tenantCode).id();
        Device device = devices.findByTenantIdAndDeviceId(tenantId, deviceId)
                .orElseThrow(() -> ApiException.notFound("Device"));
        if (device.getStatus() != DeviceStatus.ACTIVE) {
            throw new ApiException(ErrorCode.CONFLICT, "Device '" + deviceId + "' is not active");
        }
        String existingClient = device.getCredentialClientId();
        if (existingClient != null) {
            String secret = provision(() -> provisioner.rotateSecret(existingClient));
            return new DeviceCredentialsResponse(deviceId, existingClient, secret);
        }
        DeviceCredential credential = provision(() -> provisioner.create(tenantCode, deviceId));
        try {
            tx.executeWithoutResult(status -> {
                Device current = devices.findById(device.getId()).orElseThrow();
                current.assignCredentialClient(credential.clientId());
                devices.saveAndFlush(current);
            });
        } catch (RuntimeException e) {
            removeClient(credential.clientId());
            throw e;
        }
        return new DeviceCredentialsResponse(deviceId, credential.clientId(), credential.clientSecret());
    }

    /**
     * The registry is updated first so the device is rejected at once; disabling the client then stops it getting
     * new tokens. If the identity provider fails the caller sees an error and may repeat the call: it is idempotent.
     */
    public DeviceResponse deactivate(String tenantCode, String deviceId) {
        UUID tenantId = tenantService.requireContext(tenantCode).id();
        Device device = tx.execute(status -> {
            Device found = devices.findByTenantIdAndDeviceId(tenantId, deviceId)
                    .orElseThrow(() -> ApiException.notFound("Device"));
            found.deactivate();
            return devices.saveAndFlush(found);
        });
        String clientId = device.getCredentialClientId();
        if (clientId != null) {
            provision(() -> {
                provisioner.disable(clientId);
                return null;
            });
        }
        return DeviceResponse.from(device);
    }

    @Transactional(readOnly = true)
    public void requireActiveDevice(UUID tenantId, String deviceId) {
        boolean active = devices.findByTenantIdAndDeviceId(tenantId, deviceId)
                .map(d -> d.getStatus() == DeviceStatus.ACTIVE)
                .orElse(false);
        if (!active) {
            throw new ApiException(
                    ErrorCode.DEVICE_NOT_REGISTERED,
                    "Device '" + deviceId + "' is not registered as an active device for this tenant");
        }
    }

    private static ApiException conflict(String deviceId) {
        return new ApiException(ErrorCode.CONFLICT, "Device '" + deviceId + "' is already registered");
    }

    private <T> T provision(Supplier<T> call) {
        try {
            return call.get();
        } catch (CredentialProvisioningException e) {
            if (e.reason() == Reason.CONFLICT) {
                throw new ApiException(ErrorCode.CONFLICT, "A credential for this device already exists");
            }
            log.error("Credential provisioning failed: {}", e.getMessage());
            throw new ApiException(
                    ErrorCode.CREDENTIAL_PROVISIONING_UNAVAILABLE,
                    "Device credentials could not be provisioned; try again later");
        }
    }

    private void removeClient(String clientId) {
        try {
            provisioner.delete(clientId);
        } catch (RuntimeException e) {
            // The original failure is what the caller needs to see; the leftover client is named for clean-up.
            log.error("Client {} could not be removed after a failed device write", clientId, e);
        }
    }
}
