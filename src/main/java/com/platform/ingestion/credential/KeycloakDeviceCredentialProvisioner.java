package com.platform.ingestion.credential;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.ingestion.credential.CredentialProvisioningException.Reason;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Creates one confidential client per device through the Keycloak admin REST API, authenticated as the
 * provisioner client. The device client can only use client_credentials and its tokens carry the tenant, the
 * device, the API audience and the ingest role, so a leaked device secret is limited to one device.
 */
@Component
public class KeycloakDeviceCredentialProvisioner implements DeviceCredentialProvisioner {

    private static final Logger log = LoggerFactory.getLogger(KeycloakDeviceCredentialProvisioner.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN_LIFESPAN_ATTRIBUTE = "access.token.lifespan";
    private static final long TOKEN_REFRESH_MARGIN_MS = 30_000;

    private record CachedToken(String value, Instant refreshAt) {}

    private final KeycloakProvisionerProperties props;
    private final Clock clock;
    private final RestClient http;
    private final Object tokenLock = new Object();
    private volatile CachedToken cachedToken;

    public KeycloakDeviceCredentialProvisioner(KeycloakProvisionerProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(props.readTimeout());
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public DeviceCredential create(String tenantCode, String deviceId) {
        String clientId = DeviceClientIds.forDevice(tenantCode, deviceId);
        String secret = newSecret();
        try {
            String internalId = createClient(clientId, secret, tenantCode, deviceId);
            try {
                configureServiceAccount(internalId, tenantCode, deviceId);
            } catch (RuntimeException e) {
                // A client without its role would be a credential that can never ingest; do not leave it behind.
                deleteQuietly(internalId, clientId);
                throw e;
            }
            return new DeviceCredential(clientId, secret);
        } catch (RestClientException e) {
            throw translate("create client " + clientId, e);
        }
    }

    @Override
    public String rotateSecret(String clientId) {
        try {
            String internalId = findInternalId(clientId)
                    .orElseThrow(() -> new CredentialProvisioningException(
                            Reason.NOT_FOUND, "Client " + clientId + " does not exist"));
            ensureTokenLifespan(internalId);
            JsonNode body = authorised(token -> http.post()
                    .uri(props.adminBase() + "/clients/" + internalId + "/client-secret")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(JsonNode.class));
            String value = body == null ? null : body.path("value").asText(null);
            if (value == null || value.isBlank()) {
                throw new CredentialProvisioningException(Reason.UNAVAILABLE, "Keycloak returned no client secret");
            }
            return value;
        } catch (RestClientException e) {
            throw translate("rotate secret of " + clientId, e);
        }
    }

    @Override
    public void disable(String clientId) {
        try {
            Optional<String> internalId = findInternalId(clientId);
            if (internalId.isEmpty()) {
                return;
            }
            ObjectNode patch = JSON.createObjectNode().put("enabled", false);
            authorised(token -> http.put()
                    .uri(props.adminBase() + "/clients/" + internalId.get())
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(patch)
                    .retrieve()
                    .toBodilessEntity());
        } catch (RestClientException e) {
            throw translate("disable client " + clientId, e);
        }
    }

    @Override
    public void delete(String clientId) {
        try {
            Optional<String> internalId = findInternalId(clientId);
            if (internalId.isPresent()) {
                deleteClient(internalId.get());
            }
        } catch (RestClientException e) {
            throw translate("delete client " + clientId, e);
        }
    }

    private String createClient(String clientId, String secret, String tenantCode, String deviceId) {
        ResponseEntity<Void> response = authorised(token -> http.post()
                .uri(props.adminBase() + "/clients")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(clientRepresentation(clientId, secret, tenantCode, deviceId))
                .retrieve()
                .toBodilessEntity());
        URI location = response.getHeaders().getLocation();
        if (location == null || location.getPath() == null) {
            throw new CredentialProvisioningException(Reason.UNAVAILABLE, "Keycloak did not return the new client");
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private ObjectNode clientRepresentation(String clientId, String secret, String tenantCode, String deviceId) {
        ObjectNode rep = JSON.createObjectNode();
        rep.put("clientId", clientId);
        rep.put("name", "Device " + deviceId + " of " + tenantCode);
        rep.put("enabled", true);
        rep.put("publicClient", false);
        rep.put("clientAuthenticatorType", "client-secret");
        rep.put("secret", secret);
        rep.put("standardFlowEnabled", false);
        rep.put("implicitFlowEnabled", false);
        rep.put("directAccessGrantsEnabled", false);
        rep.put("serviceAccountsEnabled", true);
        rep.put("protocol", "openid-connect");
        // Restricting scope to one role would need a scope mapping, which requires the realm-wide manage-realm
        // role; instead the service account is left holding only the ingest role (see configureServiceAccount).
        rep.put("fullScopeAllowed", true);
        rep.putObject("attributes").put(TOKEN_LIFESPAN_ATTRIBUTE, props.deviceTokenLifespanSeconds());
        return rep;
    }

    /**
     * Clients created before the lifespan was set carry no attribute and would keep the realm default, so rotation
     * brings them in line. The full representation is sent back because a partial update replaces nested fields.
     */
    private void ensureTokenLifespan(String internalId) {
        JsonNode current = authorised(token -> http.get()
                .uri(props.adminBase() + "/clients/" + internalId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .retrieve()
                .body(JsonNode.class));
        if (!(current instanceof ObjectNode client)) {
            throw new CredentialProvisioningException(Reason.UNAVAILABLE, "Keycloak returned an empty response");
        }
        String wanted = props.deviceTokenLifespanSeconds();
        JsonNode existing = client.path("attributes").path(TOKEN_LIFESPAN_ATTRIBUTE);
        if (existing.isTextual() && wanted.equals(existing.asText())) {
            return;
        }
        ObjectNode update = client.deepCopy();
        ObjectNode attributes = update.path("attributes") instanceof ObjectNode a ? a : update.putObject("attributes");
        attributes.put(TOKEN_LIFESPAN_ATTRIBUTE, wanted);
        authorised(token -> http.put()
                .uri(props.adminBase() + "/clients/" + internalId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(update)
                .retrieve()
                .toBodilessEntity());
    }

    /**
     * Claims come from attributes of the service-account user through a shared client scope, because creating
     * per-client protocol mappers would need the realm-wide manage-realm role. The attributes are admin-only in the
     * realm's user profile, so the device cannot edit them itself.
     */
    private void configureServiceAccount(String internalId, String tenantCode, String deviceId) {
        JsonNode user = authorised(token -> http.get()
                .uri(props.adminBase() + "/clients/" + internalId + "/service-account-user")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .retrieve()
                .body(JsonNode.class));
        String scopeId = findClientScopeId(props.deviceScope());
        if (user == null) {
            throw new CredentialProvisioningException(Reason.UNAVAILABLE, "Keycloak returned an empty response");
        }
        String userId = user.path("id").asText();

        // The full representation is sent back: a partial one fails user-profile validation.
        ObjectNode update = ((ObjectNode) user).deepCopy();
        ObjectNode attributes = update.putObject("attributes");
        attributes.putArray("tenant_id").add(tenantCode);
        attributes.putArray("device_id").add(deviceId);
        authorised(token -> http.put()
                .uri(props.adminBase() + "/users/" + userId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(update)
                .retrieve()
                .toBodilessEntity());

        authorised(token -> http.put()
                .uri(props.adminBase() + "/clients/" + internalId + "/default-client-scopes/" + scopeId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .retrieve()
                .toBodilessEntity());

        // Roles are looked up through the user's own role-mapping endpoints, which manage-users covers; reading
        // realm roles directly would need view-realm.
        JsonNode mapped = authorised(token -> http.get()
                .uri(props.adminBase() + "/users/" + userId + "/role-mappings/realm")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .retrieve()
                .body(JsonNode.class));
        JsonNode available = authorised(token -> http.get()
                .uri(props.adminBase() + "/users/" + userId + "/role-mappings/realm/available")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .retrieve()
                .body(JsonNode.class));
        JsonNode ingestRole = null;
        if (available != null && available.isArray()) {
            for (JsonNode candidate : available) {
                if (props.ingestRole().equals(candidate.path("name").asText())) {
                    ingestRole = candidate;
                }
            }
        }
        if (ingestRole == null) {
            throw new CredentialProvisioningException(
                    Reason.UNAVAILABLE, "Realm role " + props.ingestRole() + " is missing or cannot be assigned");
        }
        ArrayNode ingestOnly = JSON.createArrayNode().add(ingestRole);
        authorised(token -> http.post()
                .uri(props.adminBase() + "/users/" + userId + "/role-mappings/realm")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(ingestOnly)
                .retrieve()
                .toBodilessEntity());
        if (mapped != null && mapped.isArray() && !mapped.isEmpty()) {
            // Keycloak gives every service account the realm default roles (account access, offline_access).
            // The device must hold the ingest role only, so they are removed.
            authorised(token -> http.method(HttpMethod.DELETE)
                    .uri(props.adminBase() + "/users/" + userId + "/role-mappings/realm")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapped)
                    .retrieve()
                    .toBodilessEntity());
        }
    }

    private String findClientScopeId(String name) {
        JsonNode scopes = authorised(token -> http.get()
                .uri(props.adminBase() + "/client-scopes")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .retrieve()
                .body(JsonNode.class));
        if (scopes != null && scopes.isArray()) {
            for (JsonNode scope : scopes) {
                if (name.equals(scope.path("name").asText())) {
                    return scope.path("id").asText();
                }
            }
        }
        throw new CredentialProvisioningException(
                Reason.UNAVAILABLE, "Client scope " + name + " is missing from the realm");
    }

    private Optional<String> findInternalId(String clientId) {
        JsonNode found = authorised(token -> http.get()
                .uri(props.adminBase() + "/clients?clientId={id}", clientId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .retrieve()
                .body(JsonNode.class));
        if (found == null || !found.isArray() || found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(found.get(0).path("id").asText());
    }

    private void deleteClient(String internalId) {
        authorised(token -> http.delete()
                .uri(props.adminBase() + "/clients/" + internalId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .retrieve()
                .toBodilessEntity());
    }

    private void deleteQuietly(String internalId, String clientId) {
        try {
            deleteClient(internalId);
        } catch (RuntimeException e) {
            log.error("Client {} could not be removed after a failed provisioning step", clientId, e);
        }
    }

    /** Runs an admin call with the cached provisioner token; a 401 means the token was revoked, so retry once. */
    private <T> T authorised(Function<String, T> call) {
        try {
            return call.apply(accessToken());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != HttpStatus.UNAUTHORIZED.value()) {
                throw e;
            }
            cachedToken = null;
            return call.apply(accessToken());
        }
    }

    private String accessToken() {
        CachedToken current = cachedToken;
        if (current != null && clock.instant().isBefore(current.refreshAt())) {
            return current.value();
        }
        synchronized (tokenLock) {
            current = cachedToken;
            if (current != null && clock.instant().isBefore(current.refreshAt())) {
                return current.value();
            }
            CachedToken fresh = fetchToken();
            cachedToken = fresh;
            return fresh.value();
        }
    }

    private CachedToken fetchToken() {
        if (props.clientSecret() == null || props.clientSecret().isBlank()) {
            throw new CredentialProvisioningException(
                    Reason.UNAVAILABLE, "The provisioner client secret is not configured");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", props.clientId());
        form.add("client_secret", props.clientSecret());
        JsonNode body = http.post()
                .uri(props.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        String token = body == null ? null : body.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new CredentialProvisioningException(Reason.UNAVAILABLE, "Keycloak returned no access token");
        }
        long lifetimeMs = body.path("expires_in").asLong(60) * 1000;
        long usableMs = Math.max(lifetimeMs / 2, lifetimeMs - TOKEN_REFRESH_MARGIN_MS);
        return new CachedToken(token, clock.instant().plusMillis(usableMs));
    }

    private static CredentialProvisioningException translate(String action, RestClientException e) {
        if (e instanceof RestClientResponseException r && r.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
            return new CredentialProvisioningException(Reason.CONFLICT, "Could not " + action + ": already exists", e);
        }
        String detail = e instanceof RestClientResponseException r
                ? "HTTP " + r.getStatusCode().value()
                : e.getClass().getSimpleName();
        // The message deliberately omits response bodies and request details: they could echo credentials.
        return new CredentialProvisioningException(Reason.UNAVAILABLE, "Could not " + action + " (" + detail + ")", e);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
