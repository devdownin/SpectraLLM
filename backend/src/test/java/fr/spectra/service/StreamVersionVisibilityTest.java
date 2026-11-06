package fr.spectra.service;

import fr.spectra.persistence.StreamSourceEntity;
import fr.spectra.persistence.StreamSourceRepository;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StreamVersionVisibilityTest {
    @Test
    void connectorSourcesAlsoHideStagedObsoleteAndMissingPublications() {
        var repository = mock(StreamSourceRepository.class);
        String key = "source://instance/document";
        var source = new StreamSourceEntity(key, "spectra_documents");
        source.setContentHash("published"); source.setVersionedPublication(true);
        when(repository.findById(key)).thenReturn(Optional.of(source));
        var visibility = new StreamVersionVisibility(repository);
        assertThat(visibility.accepts("published", key)).isTrue();
        assertThat(visibility.accepts("staged", key)).isFalse();
        assertThat(visibility.accepts(null, key)).isFalse();
        assertThat(visibility.accepts("v", "source://instance/missing")).isFalse();
        source.setContentHash(null);
        assertThat(visibility.accepts("published", key)).isFalse();
    }
    @Test
    void onlyPublishedRevisionIsVisibleAndDeletionIntentHidesAllRevisions() {
        var repository = mock(StreamSourceRepository.class);
        var source = new StreamSourceEntity("kafka://t/key", "stream");
        source.setContentHash("published");
        when(repository.findById("kafka://t/key")).thenReturn(Optional.of(source));
        var visibility = new StreamVersionVisibility(repository);
        assertThat(visibility.accepts("staged", "kafka://t/key")).isFalse();
        assertThat(visibility.accepts("published", "kafka://t/key")).isTrue();
        assertThat(visibility.accepts(null, "kafka://t/key")).isTrue();
        source.setVersionedPublication(true);
        assertThat(visibility.accepts(null, "kafka://t/key")).isFalse();
        assertThat(visibility.accepts("published", "kafka://t/key")).isTrue();
        source.setContentHash(null);
        assertThat(visibility.accepts("published", "kafka://t/key")).isFalse();
        assertThat(visibility.accepts(null, "kafka://t/key")).isFalse();
        assertThat(visibility.accepts("any", "document.pdf")).isTrue();
    }

    @Test
    void unknownKafkaSourceFailsClosed() {
        var repository = mock(StreamSourceRepository.class);
        when(repository.findById("kafka://t/missing")).thenReturn(Optional.empty());
        assertThat(new StreamVersionVisibility(repository).accepts("v", "kafka://t/missing")).isFalse();
    }
}
