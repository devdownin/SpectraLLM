package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Graphe HNSW des nouvelles collections : défauts inchangés et réglages de l'audit du retrieval. */
class QdrantStoreIndexSettingsTest {

    private final QdrantStore store = new QdrantStore(null, "http://qdrant:6333", new ObjectMapper(),
            new SimpleMeterRegistry());

    @Test
    void parDefautSeulEfConstructEstFixeEtMResteAQdrant() {
        assertThat(store.hnswConfig()).isEqualTo(Map.of("ef_construct", 200));
    }

    @Test
    void mEtEfConstructConfiguresSontTransmis() {
        store.configureIndex(32, 400);
        assertThat(store.hnswConfig()).isEqualTo(Map.of("ef_construct", 400, "m", 32));
    }

    @Test
    void reglagesHorsBornesRefuses() {
        assertThatThrownBy(() -> store.configureIndex(-1, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.configureIndex(65, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.configureIndex(16, 3)).isInstanceOf(IllegalArgumentException.class);
    }
}
