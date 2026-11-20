package com.platform.ingestion.credential;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Derives the Keycloak client id of a device: {@code device-<tenant>-<readable device>-<16 hex>}.
 *
 * <p>Device ids may contain upper case, '_' and ':'; Keycloak lower-cases the username of a client's service
 * account ({@code service-account-<clientId>}), so two ids differing only by case would collide there, and a
 * readable rule cannot be injective once '-' appears in both the tenant code and the device id. The readable
 * part is therefore lower-cased with every character outside [a-z0-9.] replaced by '-', and a hash of the exact
 * (tenant, device) pair is appended to keep distinct pairs distinct. The result is deterministic, at most
 * 7 + 31 + 1 + 64 + 1 + 16 = 120 characters, and uses only [a-z0-9.-].
 */
public final class DeviceClientIds {

    private static final int HASH_HEX_CHARS = 16;

    private DeviceClientIds() {}

    public static String forDevice(String tenantCode, String deviceId) {
        String readable = deviceId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]", "-");
        return "device-" + tenantCode + "-" + readable + "-" + hash(tenantCode, deviceId);
    }

    private static String hash(String tenantCode, String deviceId) {
        try {
            // NUL cannot occur in either part, so ("a", "bc") and ("ab", "c") hash differently.
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((tenantCode + "\0" + deviceId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, HASH_HEX_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
