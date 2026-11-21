package com.platform.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.platform.ingestion.messaging.EventPublisher;
import com.platform.ingestion.messaging.KafkaSaslConfig;
import com.platform.ingestion.messaging.MessagingConfig;
import com.platform.ingestion.messaging.OutboxMessage;
import com.platform.ingestion.messaging.OutboxProperties;
import com.platform.ingestion.messaging.PlatformKafkaProperties;
import com.platform.ingestion.messaging.TopicProvisioner;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Outbox publish and topic creation against a broker that only accepts SASL_PLAINTEXT with SCRAM-SHA-512. The
 * application's own application.yml and Kafka wiring are loaded, so the environment variables a deployment sets
 * (KAFKA_SECURITY_PROTOCOL, KAFKA_SASL_*) are what is exercised.
 */
class KafkaSaslIT {

    private static final String USER = "sensor-app";
    private static final String PASSWORD = "scram-secret-pw";
    private static final ScramKafkaContainer KAFKA = new ScramKafkaContainer(USER, PASSWORD);

    @BeforeAll
    static void start() {
        KAFKA.start();
    }

    @AfterAll
    static void stop() {
        KAFKA.stop();
    }

    @Configuration(proxyBeanMethods = false)
    @Import({MessagingConfig.class, KafkaSaslConfig.class})
    @EnableConfigurationProperties({PlatformKafkaProperties.class, OutboxProperties.class})
    static class Wiring {}

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
                .withUserConfiguration(Wiring.class)
                .withPropertyValues(
                        "KAFKA_BOOTSTRAP_SERVERS=" + KAFKA.bootstrapServers(),
                        "KAFKA_SECURITY_PROTOCOL=SASL_PLAINTEXT",
                        "KAFKA_SASL_MECHANISM=SCRAM-SHA-512",
                        "KAFKA_SASL_USERNAME=" + USER,
                        "KAFKA_SASL_PASSWORD=" + PASSWORD,
                        "platform.kafka.admin-timeout-ms=10000");
    }

    @Test
    void outboxPublishCreatesTheTopicAndDeliversWithSaslScram() {
        String topic = "sasl-" + UUID.randomUUID() + ".readings";
        String eventId = UUID.randomUUID().toString();
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            EventPublisher publisher = context.getBean(EventPublisher.class);

            publisher
                    .publish(new OutboxMessage(topic, "dev-1", "{\"v\":1}", eventId, "sasl", "1.0"))
                    .get(20, TimeUnit.SECONDS);

            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(clientConfig())) {
                consumer.subscribe(List.of(topic));
                ConsumerRecord<String, String> record = null;
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (record == null && System.nanoTime() < deadline) {
                    for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                        record = r;
                    }
                }
                assertThat(record).isNotNull();
                assertThat(record.value()).isEqualTo("{\"v\":1}");
                assertThat(new String(record.headers().lastHeader("event_id").value()))
                        .isEqualTo(eventId);
            }
            try (Admin admin = Admin.create(clientConfig())) {
                assertThat(admin.listTopics().names().get(10, TimeUnit.SECONDS)).contains(topic);
            }
        });
    }

    @Test
    void aClientWithoutCredentialsCannotCreateTopics() {
        // Same broker, plaintext default: proves that the broker really enforces SASL.
        Map<String, Object> plain = Map.of(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, KAFKA.bootstrapServers());
        TopicProvisioner provisioner = new TopicProvisioner(plain, 1, (short) 1, Duration.ofSeconds(3));

        assertThat(provisioner.tryEnsureTopic("anonymous.readings")).isFalse();
    }

    private static Map<String, Object> clientConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, KAFKA.bootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "sasl-it-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT");
        config.put(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-512");
        config.put(
                SaslConfigs.SASL_JAAS_CONFIG,
                "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"" + USER + "\" password=\""
                        + PASSWORD + "\";");
        return config;
    }

    /**
     * Single KRaft node whose client listener requires SCRAM-SHA-512. The stock image cannot do that through
     * environment variables: the SCRAM user has to be written into the cluster metadata while the storage is
     * formatted (kafka-storage --add-scram), so the configuration and start script are supplied here. The advertised
     * port is only known once the container is mapped, as in SingleNodeKafkaContainer.
     */
    static final class ScramKafkaContainer extends GenericContainer<ScramKafkaContainer> {

        private static final int CLIENT_PORT = 9092;
        private static final String CLUSTER_ID = "MkU3OEVBNTcwNTJENDM2Qk";
        private final String user;
        private final String password;

        ScramKafkaContainer(String user, String password) {
            super(DockerImageName.parse("apache/kafka:3.9.0"));
            this.user = user;
            this.password = password;
            withExposedPorts(CLIENT_PORT);
            withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh", "-c"));
            // one argument, so that "sh -c" receives the whole script
            withCommand(new String[] {"while [ ! -f /tmp/start.sh ]; do sleep 0.1; done; bash /tmp/start.sh"});
            waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1).withStartupTimeout(Duration.ofMinutes(2)));
        }

        String bootstrapServers() {
            return getHost() + ":" + getMappedPort(CLIENT_PORT);
        }

        @Override
        protected void containerIsStarting(InspectContainerResponse containerInfo) {
            String properties = String.join(
                    "\n",
                    "process.roles=broker,controller",
                    "node.id=1",
                    "controller.quorum.voters=1@localhost:9093",
                    "listeners=CLIENT://:9092,BROKER://:9095,CONTROLLER://:9093",
                    "advertised.listeners=CLIENT://" + bootstrapServers() + ",BROKER://localhost:9095,"
                            + "CONTROLLER://localhost:9093",
                    "listener.security.protocol.map=CLIENT:SASL_PLAINTEXT,BROKER:PLAINTEXT,CONTROLLER:PLAINTEXT",
                    "inter.broker.listener.name=BROKER",
                    "controller.listener.names=CONTROLLER",
                    "sasl.enabled.mechanisms=SCRAM-SHA-512",
                    "listener.name.client.scram-sha-512.sasl.jaas.config="
                            + "org.apache.kafka.common.security.scram.ScramLoginModule required;",
                    "auto.create.topics.enable=false",
                    "offsets.topic.replication.factor=1",
                    "transaction.state.log.replication.factor=1",
                    "transaction.state.log.min.isr=1",
                    "group.initial.rebalance.delay.ms=0",
                    "log.dirs=/tmp/kraft-logs",
                    "");
            String script = "#!/bin/bash\nset -e\n"
                    + "/opt/kafka/bin/kafka-storage.sh format --ignore-formatted -t " + CLUSTER_ID
                    + " -c /tmp/server.properties --add-scram 'SCRAM-SHA-512=[name=" + user + ",password=" + password
                    + "]'\n"
                    + "exec /opt/kafka/bin/kafka-server-start.sh /tmp/server.properties\n";
            copyFileToContainer(Transferable.of(properties), "/tmp/server.properties");
            copyFileToContainer(Transferable.of(script, 0777), "/tmp/start.sh");
        }
    }
}
