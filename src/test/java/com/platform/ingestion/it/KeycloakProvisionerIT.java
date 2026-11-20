package com.platform.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.ingestion.credential.CredentialProvisioningException;
import com.platform.ingestion.credential.CredentialProvisioningException.Reason;
import com.platform.ingestion.credential.DeviceCredential;
import com.platform.ingestion.credential.KeycloakDeviceCredentialProvisioner;
import com.platform.ingestion.credential.KeycloakProvisionerProperties;
import com.platform.ingestion.security.AudienceValidator;
import com.platform.ingestion.security.KeycloakJwtAuthenticationConverter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Runs the provisioner against a real Keycloak imported from the repository's realm export, so the export's
 * provisioner role set, the admin API permissions it needs and the tokens of the created clients are all
 * exercised together.
 */
class KeycloakProvisionerIT {

    private static final String REALM = "sensor-platform";
    private static final String PROVISIONER_SECRET = "provisioner-it-secret";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REALM_EXPORT = Path.of("infra/keycloak/sensor-platform-realm.json");

    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(DockerImageName.parse(composeImage()))
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin-it-pw")
            // placeholders of the realm export
            .withEnv("DEMO_USER_PASSWORD", "demo-it-pw")
            .withEnv("ALPHA_INGEST_CLIENT_SECRET", "alpha-it-secret")
            .withEnv("BETA_INGEST_CLIENT_SECRET", "beta-it-secret")
            .withEnv("PROVISIONER_CLIENT_SECRET", PROVISIONER_SECRET)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(REALM_EXPORT), "/opt/keycloak/data/import/sensor-platform-realm.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/" + REALM)
                    .forPort(8080)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private static KeycloakDeviceCredentialProvisioner provisioner;
    private static String baseUrl;

    /** Same image as the compose stack, so the test cannot drift from what runs locally. */
    private static String composeImage() {
        try {
            Matcher m = Pattern.compile("image:\\s*(quay\\.io/keycloak/keycloak:\\S+)")
                    .matcher(Files.readString(Path.of("docker-compose.yml")));
            if (!m.find()) {
                throw new IllegalStateException("No Keycloak image in docker-compose.yml");
            }
            return m.group(1);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeycloakProvisionerProperties properties(String url, String secret) {
        return new KeycloakProvisionerProperties(
                url,
                REALM,
                "sensor-platform-provisioner",
                secret,
                "device-identity",
                "tenant-ingest",
                Duration.ofSeconds(2),
                Duration.ofSeconds(15));
    }

    @BeforeAll
    static void startKeycloak() {
        KEYCLOAK.start();
        baseUrl = "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
        provisioner =
                new KeycloakDeviceCredentialProvisioner(properties(baseUrl, PROVISIONER_SECRET), Clock.systemUTC());
    }

    private record TokenReply(int status, JsonNode body) {}

    private static TokenReply clientCredentials(String clientId, String secret) throws Exception {
        return tokenRequest(
                "grant_type=client_credentials&client_id=" + enc(clientId) + "&client_secret=" + enc(secret));
    }

    private static TokenReply tokenRequest(String form) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/realms/" + REALM + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return new TokenReply(response.statusCode(), JSON.readTree(response.body()));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String uniqueDevice() {
        return "IT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase() + ":1";
    }

    private static JsonNode claims(String accessToken) throws Exception {
        return JSON.readTree(java.util.Base64.getUrlDecoder().decode(accessToken.split("[.]")[1]));
    }

    @Test
    void createdClientGetsATokenWithTenantDeviceAudienceAndOnlyTheIngestRole() throws Exception {
        String device = uniqueDevice();

        DeviceCredential credential = provisioner.create("tenant-alpha", device);

        TokenReply token = clientCredentials(credential.clientId(), credential.clientSecret());
        assertThat(token.status()).isEqualTo(200);
        String accessToken = token.body().get("access_token").asText();
        JsonNode claims = claims(accessToken);
        assertThat(claims.get("tenant_id").asText()).isEqualTo("tenant-alpha");
        assertThat(claims.get("device_id").asText()).isEqualTo(device);
        assertThat(claims.get("aud").toString()).isEqualTo("\"sensor-platform-api\"");
        assertThat(claims.get("realm_access").get("roles")).hasSize(1);
        assertThat(claims.get("realm_access").get("roles").get(0).asText()).isEqualTo("tenant-ingest");
        assertThat(claims.has("resource_access")).isFalse();

        // What the application does with it: signature and audience via the realm's keys, roles via its converter.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(
                        baseUrl + "/realms/" + REALM + "/protocol/openid-connect/certs")
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), new AudienceValidator("sensor-platform-api")));
        Jwt jwt = decoder.decode(accessToken);
        List<String> authorities = new KeycloakJwtAuthenticationConverter()
                .convert(jwt).getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .toList();
        assertThat(authorities).containsExactly("ROLE_TENANT_INGEST");

        // Only client_credentials is enabled: the password grant is refused for this client.
        TokenReply password = tokenRequest("grant_type=password&client_id=" + enc(credential.clientId())
                + "&client_secret=" + enc(credential.clientSecret()) + "&username=platform-admin&password=demo-it-pw");
        assertThat(password.status()).isGreaterThanOrEqualTo(400);
        assertThat(password.body().get("error").asText()).isEqualTo("unauthorized_client");
    }

    @Test
    void disablingTheClientRefusesNewTokens() throws Exception {
        DeviceCredential credential = provisioner.create("tenant-alpha", uniqueDevice());
        assertThat(clientCredentials(credential.clientId(), credential.clientSecret())
                        .status())
                .isEqualTo(200);

        provisioner.disable(credential.clientId());
        provisioner.disable(credential.clientId());

        assertThat(clientCredentials(credential.clientId(), credential.clientSecret())
                        .status())
                .isGreaterThanOrEqualTo(400);
        // Removing an unknown client is not an error, so retries and compensation are safe.
        provisioner.disable("device-no-such-client");
        provisioner.delete("device-no-such-client");
    }

    @Test
    void rotationInvalidatesTheOldSecretAndKeepsTheClient() throws Exception {
        DeviceCredential credential = provisioner.create("tenant-alpha", uniqueDevice());

        String rotated = provisioner.rotateSecret(credential.clientId());

        assertThat(rotated).isNotEqualTo(credential.clientSecret());
        assertThat(clientCredentials(credential.clientId(), credential.clientSecret())
                        .status())
                .isGreaterThanOrEqualTo(400);
        TokenReply fresh = clientCredentials(credential.clientId(), rotated);
        assertThat(fresh.status()).isEqualTo(200);
        assertThat(claims(fresh.body().get("access_token").asText())
                        .get("client_id")
                        .asText())
                .isEqualTo(credential.clientId());
    }

    @Test
    void creatingTheSameDeviceTwiceConflictsAndDeleteRemovesTheClient() throws Exception {
        String device = uniqueDevice();
        DeviceCredential credential = provisioner.create("tenant-alpha", device);

        assertThatThrownBy(() -> provisioner.create("tenant-alpha", device))
                .isInstanceOfSatisfying(CredentialProvisioningException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.CONFLICT));

        provisioner.delete(credential.clientId());
        assertThat(clientCredentials(credential.clientId(), credential.clientSecret())
                        .status())
                .isGreaterThanOrEqualTo(400);
        // Gone, so the same device can be provisioned again.
        provisioner.delete(provisioner.create("tenant-alpha", device).clientId());
    }

    @Test
    void provisionerTokenCannotBeUsedAgainstThePlatformApi() throws Exception {
        TokenReply token = clientCredentials("sensor-platform-provisioner", PROVISIONER_SECRET);

        JsonNode claims = claims(token.body().get("access_token").asText());
        assertThat(claims.get("aud").toString()).doesNotContain("sensor-platform-api");
        assertThat(claims.has("realm_access") ? claims.get("realm_access").toString() : "")
                .doesNotContain("tenant-ingest")
                .doesNotContain("platform-admin")
                .doesNotContain("tenant-admin");
        assertThat(claims.has("tenant_id")).isFalse();
        // Least privilege: no manage-realm (it would allow realm-wide changes), only what provisioning needs.
        List<String> realmManagement = new java.util.ArrayList<>();
        claims.get("resource_access")
                .get("realm-management")
                .get("roles")
                .forEach(r -> realmManagement.add(r.asText()));
        assertThat(realmManagement).containsExactlyInAnyOrder("manage-clients", "manage-users");
    }

    @Test
    void deviceCredentialCannotChangeItsOwnIdentityClaims() throws Exception {
        String device = uniqueDevice();
        DeviceCredential credential = provisioner.create("tenant-alpha", device);
        String token = clientCredentials(credential.clientId(), credential.clientSecret())
                .body()
                .get("access_token")
                .asText();
        String userId = claims(token).get("sub").asText();

        // Neither the account API nor the admin API accepts the device token (no account or admin audience).
        for (String url : List.of(
                baseUrl + "/realms/" + REALM + "/account/", baseUrl + "/admin/realms/" + REALM + "/users/" + userId)) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"attributes\":{\"tenant_id\":[\"tenant-beta\"]}}"))
                    .build();
            int status = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString())
                    .statusCode();
            assertThat(status).isIn(401, 403, 404, 405);
        }
        // The claims in a fresh token are unchanged.
        JsonNode fresh = claims(clientCredentials(credential.clientId(), credential.clientSecret())
                .body()
                .get("access_token")
                .asText());
        assertThat(fresh.get("tenant_id").asText()).isEqualTo("tenant-alpha");
        assertThat(fresh.get("device_id").asText()).isEqualTo(device);
    }

    @Test
    void unreachableOrRejectingKeycloakSurfacesAsUnavailableWithoutLeakingTheSecret() {
        KeycloakDeviceCredentialProvisioner unreachable = new KeycloakDeviceCredentialProvisioner(
                properties("http://127.0.0.1:1", "the-secret"), Clock.systemUTC());
        KeycloakDeviceCredentialProvisioner wrongSecret = new KeycloakDeviceCredentialProvisioner(
                properties(baseUrl, "not-the-provisioner-secret"), Clock.systemUTC());

        for (KeycloakDeviceCredentialProvisioner p : List.of(unreachable, wrongSecret)) {
            assertThatThrownBy(() -> p.create("tenant-alpha", "M-1"))
                    .isInstanceOfSatisfying(CredentialProvisioningException.class, e -> {
                        assertThat(e.reason()).isEqualTo(Reason.UNAVAILABLE);
                        assertThat(e.getMessage()).doesNotContain("the-secret").doesNotContain("not-the-provisioner");
                    });
        }
    }
}
