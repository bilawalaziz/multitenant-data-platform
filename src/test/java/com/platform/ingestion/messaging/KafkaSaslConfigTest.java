package com.platform.ingestion.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KafkaSaslConfigTest {

    private static Map<String, String> props(String protocol, String mechanism) {
        Map<String, String> p = new HashMap<>();
        p.put("security.protocol", protocol);
        if (mechanism != null) {
            p.put("sasl.mechanism", mechanism);
        }
        return p;
    }

    @Test
    void plaintextAndTlsOnlyGetNoLogin() {
        for (String protocol : new String[] {"PLAINTEXT", "SSL"}) {
            Map<String, String> p = props(protocol, "SCRAM-SHA-512");
            KafkaSaslConfig.applySasl(p, "u", "pw");
            assertThat(p).doesNotContainKey("sasl.jaas.config");
        }
    }

    @Test
    void scramGetsTheScramLoginModule() {
        Map<String, String> p = props("SASL_SSL", "SCRAM-SHA-512");
        KafkaSaslConfig.applySasl(p, "sensor_app", "pw");
        assertThat(p.get("sasl.jaas.config"))
                .isEqualTo("org.apache.kafka.common.security.scram.ScramLoginModule required "
                        + "username=\"sensor_app\" password=\"pw\";");
    }

    @Test
    void plainGetsThePlainLoginModuleAndQuotesAreEscaped() {
        Map<String, String> p = props("SASL_PLAINTEXT", "PLAIN");
        KafkaSaslConfig.applySasl(p, "u", "a\"b\\c");
        assertThat(p.get("sasl.jaas.config"))
                .startsWith("org.apache.kafka.common.security.plain.PlainLoginModule required")
                .contains("password=\"a\\\"b\\\\c\"");
    }

    @Test
    void saslWithoutCredentialsFailsAtStartup() {
        assertThatThrownBy(() -> KafkaSaslConfig.applySasl(props("SASL_SSL", "SCRAM-SHA-512"), "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KAFKA_SASL_USERNAME");
    }

    @Test
    void anExplicitLoginIsKept() {
        Map<String, String> p = props("SASL_SSL", "SCRAM-SHA-512");
        p.put("sasl.jaas.config", "custom");
        KafkaSaslConfig.applySasl(p, "u", "pw");
        assertThat(p.get("sasl.jaas.config")).isEqualTo("custom");
    }
}
