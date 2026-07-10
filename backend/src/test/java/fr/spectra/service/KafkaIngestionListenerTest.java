package fr.spectra.service;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie le mapping clé Kafka → identité métier RAG, sans broker.
 */
class KafkaIngestionListenerTest {

    private ConsumerRecord<String, byte[]> record(String topic, int partition, long offset, String key) {
        return new ConsumerRecord<>(topic, partition, offset, key, new byte[0]);
    }

    @Test
    void withKey_usesBusinessIdentity() {
        String src = KafkaIngestionListener.buildSourceKey(record("commandes", 0, 42, "dossier-4271"));
        assertThat(src).isEqualTo("kafka://commandes/dossier-4271");
    }

    @Test
    void withoutKey_fallsBackToPartitionOffset() {
        String src = KafkaIngestionListener.buildSourceKey(record("events", 3, 128, null));
        assertThat(src).isEqualTo("kafka://events/3-128");
    }

    @Test
    void blankKey_fallsBackToPartitionOffset() {
        String src = KafkaIngestionListener.buildSourceKey(record("events", 1, 7, "  "));
        assertThat(src).isEqualTo("kafka://events/1-7");
    }
}
