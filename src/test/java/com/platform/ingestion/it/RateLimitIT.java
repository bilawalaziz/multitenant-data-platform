package com.platform.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.observability.PlatformMetrics;
import com.platform.ingestion.ratelimit.RateLimitProperties;
import com.platform.ingestion.ratelimit.RateLimiter;
import com.platform.ingestion.ratelimit.RedisProxyManagerSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The base class turns rate limiting off so the other tests are not bound by limits or a Redis container; this
 * class turns it back on with small limits and a real Redis. Refill is slow enough that no token returns while a
 * test runs, and each test uses tenants of its own because buckets persist in Redis.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(
        properties = {
            "platform.rate-limit.enabled=true",
            "platform.rate-limit.failure-cooldown=1s",
            "platform.rate-limit.tenant.capacity=6",
            "platform.rate-limit.tenant.refill-per-second=0.05",
            "platform.rate-limit.device.capacity=3",
            "platform.rate-limit.device.refill-per-second=0.05"
        })
class RateLimitIT extends AbstractIntegrationTest {

    private static final String REDIS_PASSWORD = "it-redis-password";
    private static final int TENANT_CAPACITY = 6;
    private static final int DEVICE_CAPACITY = 3;

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.12-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private RedisProperties redisProperties;

    @Autowired
    private RateLimitProperties limits;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private PlatformMetrics platformMetrics;

    private final AtomicInteger seq = new AtomicInteger();

    /** A tenant with the demo alpha contract and the given devices registered. */
    private String newTenant(String... devices) throws Exception {
        String tenant = "tenant-rl-" + seq.incrementAndGet() + "-"
                + UUID.randomUUID().toString().substring(0, 4);
        registerTenant(tenant);
        JsonNode created = body(uploadContract(
                        tenant,
                        tenantAdmin(tenant),
                        "rl-readings",
                        demoFile("tenant-alpha/openapi.yaml"),
                        demoFile("tenant-alpha/mapping.json"))
                .andExpect(status().isCreated())
                .andReturn());
        activate(
                        tenant,
                        tenantAdmin(tenant),
                        created.get("contractId").asText(),
                        created.get("version").asInt())
                .andExpect(status().isOk());
        for (String device : devices) {
            registerDevice(tenant, device);
        }
        return tenant;
    }

    private String payloadFor(String deviceId) throws Exception {
        ObjectNode node =
                (ObjectNode) mapper.readTree(new String(demoFile("payloads/alpha-valid.json"), StandardCharsets.UTF_8));
        node.put("deviceId", deviceId);
        return mapper.writeValueAsString(node);
    }

    private static String deviceToken(String tenant, String device) {
        return "tenant-ingest." + tenant + "." + device;
    }

    private double rateLimitedRejections() {
        var counter = registry.find("ingestion.rejections")
                .tag("reason", "RATE_LIMITED")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    /** Lets the circuit breaker close again so the tests that follow are limited as usual. */
    private void awaitLimiterRecovered() throws InterruptedException {
        for (int attempt = 0; attempt < 10; attempt++) {
            Thread.sleep(1200);
            double errors = limiterErrors();
            rateLimiter.checkTenant("tenant-recovery-" + UUID.randomUUID());
            if (limiterErrors() == errors) {
                return;
            }
        }
        throw new AssertionError("rate limiter did not recover after Redis was resumed");
    }

    private double counter(String name) {
        var counter = registry.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    private double limiterErrors() {
        return counter("ratelimit.errors");
    }

    @Test
    void tenantOverTheLimitGetsRateLimitedWithRetryAfterWhileOtherTenantsAreUnaffected() throws Exception {
        // One request per device keeps every device bucket far from its own limit.
        List<String> devices = new ArrayList<>();
        for (int i = 0; i <= TENANT_CAPACITY; i++) {
            devices.add("MACHINE-" + (200 + i));
        }
        String noisy = newTenant(devices.toArray(String[]::new));
        String quiet = newTenant("MACHINE-300");
        double rejectionsBefore = rateLimitedRejections();

        for (int i = 0; i < TENANT_CAPACITY; i++) {
            ingest(tenantIngest(noisy), uniqueKey(), payloadFor(devices.get(i))).andExpect(status().isAccepted());
        }
        String key = uniqueKey();
        MvcResult limited = ingest(tenantIngest(noisy), key, payloadFor(devices.get(TENANT_CAPACITY)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"))
                .andReturn();
        long retryAfter = Long.parseLong(limited.getResponse().getHeader("Retry-After"));
        // 0.05 tokens per second: about 20 s until the next token.
        assertThat(retryAfter).isBetween(1L, 21L);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.ingestion_records WHERE idempotency_key = ?",
                        Integer.class,
                        key))
                .isZero();

        // The check precedes parsing, so even an unparseable body is answered with 429 and costs no database work.
        ingest(tenantIngest(noisy), uniqueKey(), "{not json").andExpect(status().isTooManyRequests());

        ingest(tenantIngest(quiet), uniqueKey(), payloadFor("MACHINE-300")).andExpect(status().isAccepted());
        assertThat(rateLimitedRejections()).isEqualTo(rejectionsBefore + 2);
    }

    @Test
    void oneNoisyDeviceIsLimitedWithoutBlockingOtherDevicesOfTheSameTenant() throws Exception {
        String tenant = newTenant("MACHINE-401", "MACHINE-402");

        for (int i = 0; i < DEVICE_CAPACITY; i++) {
            ingest(deviceToken(tenant, "MACHINE-401"), uniqueKey(), payloadFor("MACHINE-401"))
                    .andExpect(status().isAccepted());
        }
        MvcResult limited = ingest(deviceToken(tenant, "MACHINE-401"), uniqueKey(), payloadFor("MACHINE-401"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"))
                .andReturn();
        assertThat(limited.getResponse().getHeader("Retry-After")).isNotBlank();

        ingest(deviceToken(tenant, "MACHINE-402"), uniqueKey(), payloadFor("MACHINE-402"))
                .andExpect(status().isAccepted());
    }

    @Test
    void tenantWideTokenIsLimitedPerDeviceOnceThePayloadIsMapped() throws Exception {
        String tenant = newTenant("MACHINE-501", "MACHINE-502");

        for (int i = 0; i < DEVICE_CAPACITY; i++) {
            ingest(tenantIngest(tenant), uniqueKey(), payloadFor("MACHINE-501")).andExpect(status().isAccepted());
        }
        ingest(tenantIngest(tenant), uniqueKey(), payloadFor("MACHINE-501")).andExpect(status().isTooManyRequests());
        ingest(tenantIngest(tenant), uniqueKey(), payloadFor("MACHINE-502")).andExpect(status().isAccepted());
    }

    @Test
    void twoLimitersOnOneRedisTogetherAdmitNoMoreThanTheCapacity() throws Exception {
        String tenant = "tenant-shared-" + UUID.randomUUID().toString().substring(0, 8);
        // Separate clients and proxy managers, as two application instances would have.
        RedisProxyManagerSource firstSource = new RedisProxyManagerSource(redisProperties);
        RedisProxyManagerSource secondSource = new RedisProxyManagerSource(redisProperties);
        RateLimiter first = new RateLimiter(limits, firstSource, platformMetrics);
        RateLimiter second = new RateLimiter(limits, secondSource, platformMetrics);
        // Connect first: callers that find a connect in progress are admitted unchecked rather than queued.
        first.checkTenant("warmup-" + tenant);
        second.checkTenant("warmup-" + tenant);

        AtomicInteger admitted = new AtomicInteger();
        List<Callable<Void>> attempts = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            RateLimiter limiter = i % 2 == 0 ? first : second;
            attempts.add(() -> {
                try {
                    limiter.checkTenant(tenant);
                    admitted.incrementAndGet();
                } catch (ApiException expected) {
                    // over the limit
                }
                return null;
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (Future<Void> f : pool.invokeAll(attempts)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
            firstSource.destroy();
            secondSource.destroy();
        }

        assertThat(admitted.get()).isEqualTo(TENANT_CAPACITY);
    }

    @Test
    void requestsAreStillAcceptedWhileRedisIsDownAndTheErrorCounterRises() throws Exception {
        String tenant = newTenant("MACHINE-601");
        // Warm up so the connection exists and the outage is a hang, not a refused connect.
        ingest(tenantIngest(tenant), uniqueKey(), payloadFor("MACHINE-601")).andExpect(status().isAccepted());
        double errorsBefore = limiterErrors();
        double skippedBefore = counter("ratelimit.skipped");
        int burst = 20;

        // A paused container accepts connections but never answers: the worst case for latency.
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        long elapsedMillis;
        try {
            long start = System.nanoTime();
            for (int i = 0; i < burst; i++) {
                ingest(tenantIngest(tenant), uniqueKey(), payloadFor("MACHINE-601"))
                        .andExpect(status().isAccepted());
            }
            elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        awaitLimiterRecovered();

        assertThat(limiterErrors()).isGreaterThan(errorsBefore);
        assertThat(counter("ratelimit.skipped")).isGreaterThan(skippedBefore);
        // Without the breaker every request would wait on Redis timeouts: at least 200 ms x 20 = 4 s.
        assertThat(elapsedMillis).isLessThan(burst * 200L);
    }
}
