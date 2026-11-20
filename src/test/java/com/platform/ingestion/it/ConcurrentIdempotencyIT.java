package com.platform.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Requests racing on one Idempotency-Key through the real HTTP stack, with the database timeouts in force. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "platform.outbox.enabled=false",
            "platform.retention.enabled=false",
            "platform.kafka.admin-timeout-ms=10000",
            "management.server.port=0"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrentIdempotencyIT extends AbstractIntegrationTest {

    private static final String TENANT = "tenant-concurrency";
    private static final int REQUESTS = 12;

    @LocalServerPort
    private int port;

    private record Reply(int status, JsonNode body) {}

    @BeforeAll
    void setUpTenant() throws Exception {
        registerTenant(TENANT);
        registerDevice(TENANT, "MACHINE-001");
        JsonNode created = body(uploadContract(
                        TENANT,
                        tenantAdmin(TENANT),
                        "concurrency-readings",
                        demoFile("tenant-alpha/openapi.yaml"),
                        demoFile("tenant-alpha/mapping.json"))
                .andExpect(status().isCreated())
                .andReturn());
        activate(
                        TENANT,
                        tenantAdmin(TENANT),
                        created.get("contractId").asText(),
                        created.get("version").asInt())
                .andExpect(status().isOk());
    }

    private String payload(double temperature) throws Exception {
        ObjectNode node =
                (ObjectNode) mapper.readTree(new String(demoFile("payloads/alpha-valid.json"), StandardCharsets.UTF_8));
        node.put("temperature", temperature);
        return mapper.writeValueAsString(node);
    }

    /** Sends REQUESTS requests that are all released at the same moment. */
    private List<Reply> race(String key, IntFunction<String> payloadOf) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        try {
            List<Future<Reply>> futures = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                String body = payloadOf.apply(i);
                futures.add(pool.submit(() -> {
                    HttpRequest request = HttpRequest.newBuilder(
                                    URI.create("http://localhost:" + port + "/api/v1/ingestion/readings"))
                            .header("Authorization", bearer(tenantIngest(TENANT)))
                            .header("Idempotency-Key", key)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    ready.countDown();
                    go.await();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    return new Reply(response.statusCode(), mapper.readTree(response.body()));
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Reply> replies = new ArrayList<>();
            for (Future<Reply> f : futures) {
                replies.add(f.get(60, TimeUnit.SECONDS));
            }
            return replies;
        } finally {
            pool.shutdownNow();
        }
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void identicalConcurrentRequestsCreateExactlyOneIngestionAndOneOutboxRow() throws Exception {
        String key = uniqueKey();
        String payload = payload(50);

        List<Reply> replies = race(key, i -> payload);

        List<Reply> accepted = replies.stream().filter(r -> r.status() == 202).toList();
        List<Reply> duplicates = replies.stream().filter(r -> r.status() == 200).toList();
        assertThat(replies).noneMatch(r -> r.status() >= 500);
        assertThat(accepted).hasSize(1);
        assertThat(duplicates).hasSize(REQUESTS - 1);
        String ingestionId = accepted.get(0).body().get("ingestionId").asText();
        assertThat(duplicates).allSatisfy(r -> {
            assertThat(r.body().get("status").asText()).isEqualTo("DUPLICATE");
            assertThat(r.body().get("ingestionId").asText()).isEqualTo(ingestionId);
        });

        assertThat(count("SELECT count(*) FROM platform.ingestion_records WHERE idempotency_key = ?", key))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM platform.outbox_events WHERE event_id IN "
                                + "(SELECT event_id FROM platform.ingestion_records WHERE idempotency_key = ?)",
                        key))
                .isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentPayloadsAcceptsOneAndRejectsTheRestAsConflict() throws Exception {
        String key = uniqueKey();

        List<Reply> replies = race(key, i -> {
            try {
                return payload(40 + i);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(replies).noneMatch(r -> r.status() >= 500);
        assertThat(replies.stream().filter(r -> r.status() == 202)).hasSize(1);
        List<Reply> conflicts = replies.stream().filter(r -> r.status() == 409).toList();
        assertThat(conflicts).hasSize(REQUESTS - 1);
        assertThat(conflicts)
                .allSatisfy(r -> assertThat(r.body().get("errorCode").asText()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT"));

        assertThat(count("SELECT count(*) FROM platform.ingestion_records WHERE idempotency_key = ?", key))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM platform.outbox_events WHERE event_id IN "
                                + "(SELECT event_id FROM platform.ingestion_records WHERE idempotency_key = ?)",
                        key))
                .isEqualTo(1);
    }
}
