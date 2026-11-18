package com.platform.ingestion.it;

import com.github.dockerjava.api.command.InspectContainerResponse;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * org.testcontainers.kafka.KafkaContainer for apache/kafka 3.9.0. In KRaft combined mode Kafka 3.9 also derives an
 * advertised address for the CONTROLLER listener; the stock container only advertises PLAINTEXT and BROKER, so the
 * controller falls back to the wildcard 0.0.0.0 and the broker refuses to start ("advertised.listeners cannot use the
 * nonroutable meta-address 0.0.0.0"). This subclass advertises the controller listener explicitly.
 */
final class SingleNodeKafkaContainer extends KafkaContainer {

    private static final String BROKER_HOST = "kafka-broker";

    SingleNodeKafkaContainer(DockerImageName image) {
        super(image);
        withCreateContainerCmdModifier(cmd -> cmd.withHostName(BROKER_HOST));
    }

    @Override
    protected void containerIsStarting(InspectContainerResponse containerInfo) {
        String advertised = "PLAINTEXT://" + getHost() + ":" + getMappedPort(9092) + ",BROKER://" + BROKER_HOST
                + ":9093,CONTROLLER://localhost:9094";
        String script =
                "#!/bin/bash\n" + "export KAFKA_ADVERTISED_LISTENERS=" + advertised + "\n" + "/etc/kafka/docker/run \n";
        copyFileToContainer(Transferable.of(script, 0777), "/tmp/testcontainers_start.sh");
    }
}
