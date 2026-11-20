package com.platform.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Actuator lives on the management port only; the API port does not serve it. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "platform.outbox.enabled=false",
            "platform.kafka.admin-timeout-ms=10000",
            "management.server.port=0"
        })
class ManagementPortIT extends AbstractIntegrationTest {

    @LocalServerPort
    private int serverPort;

    @Value("${local.management.port}")
    private int managementPort;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthAndMetricsAreServedOnTheManagementPortOnly() throws Exception {
        assertThat(managementPort).isNotEqualTo(serverPort);

        HttpResponse<String> health = get(managementPort, "/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"UP\"");

        HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body())
                .contains("ingestion_requests_total{", "outbox_publish_total{")
                .contains("outbox_pending_events", "outbox_failed_events", "ingestion_processing_seconds_bucket");

        assertThat(get(serverPort, "/actuator/prometheus").statusCode()).isEqualTo(404);
        assertThat(get(serverPort, "/actuator/health").statusCode()).isEqualTo(404);
    }

    @Test
    void otherActuatorEndpointsAreNotAvailableAnonymously() throws Exception {
        assertThat(get(managementPort, "/actuator/env").statusCode()).isIn(401, 403, 404);
        assertThat(get(managementPort, "/actuator/beans").statusCode()).isIn(401, 403, 404);
    }
}
