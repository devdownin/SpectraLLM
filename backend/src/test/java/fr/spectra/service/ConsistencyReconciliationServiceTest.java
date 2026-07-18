package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.persistence.IngestedFileRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Réconciliation multi-collections : la collection par défaut n'est plus la seule
 * surveillée — les collections référencées par la GED et celle du flux Kafka le sont aussi.
 */
class ConsistencyReconciliationServiceTest {

    private static final String DEFAULT_COLLECTION = "spectra_documents";

    private IngestedFileRepository fileRepo = mock(IngestedFileRepository.class);
    private ChromaDbClient chromaDb = mock(ChromaDbClient.class);
    private FtsService fts = mock(FtsService.class);

    private ConsistencyReconciliationService service(SpectraProperties.KafkaProperties kafka) {
        SpectraProperties.ChromaDbProperties chromaProps =
                new SpectraProperties.ChromaDbProperties("http://chroma:8000", DEFAULT_COLLECTION);
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.chromadb()).thenReturn(chromaProps);
        when(props.kafka()).thenReturn(kafka);
        return new ConsistencyReconciliationService(fileRepo, chromaDb, fts, props, new SimpleMeterRegistry());
    }

    private static SpectraProperties.KafkaProperties kafka(boolean enabled, String collection) {
        return new SpectraProperties.KafkaProperties(enabled, null, null, null, collection,
                null, null, null, null, null, null, null, null, null);
    }

    @Test
    void targetCollections_includesDefaultGedAndKafkaCollections() {
        when(fileRepo.findDistinctCollectionNames())
                .thenReturn(List.of(DEFAULT_COLLECTION, "collection_projet"));

        Set<String> collections = service(kafka(true, "spectra_stream")).targetCollections();

        assertThat(collections).containsExactlyInAnyOrder(
                DEFAULT_COLLECTION, "collection_projet", "spectra_stream");
    }

    @Test
    void targetCollections_kafkaDisabled_streamCollectionNotWatched() {
        when(fileRepo.findDistinctCollectionNames()).thenReturn(List.of());

        Set<String> collections = service(kafka(false, "spectra_stream")).targetCollections();

        assertThat(collections).containsExactly(DEFAULT_COLLECTION);
    }

    @Test
    void reconcile_divergenceOnSecondaryCollection_triggersRebuild() {
        when(fileRepo.findDistinctCollectionNames()).thenReturn(List.of("collection_projet"));
        when(fileRepo.sumChunks()).thenReturn(12L);
        when(chromaDb.getOrCreateCollection(DEFAULT_COLLECTION)).thenReturn("id-default");
        when(chromaDb.getOrCreateCollection("collection_projet")).thenReturn("id-projet");
        // Défaut cohérent ; collection secondaire divergente (chroma=8, fts=0).
        when(chromaDb.count("id-default")).thenReturn(5);
        when(fts.indexedCount(DEFAULT_COLLECTION)).thenReturn(5);
        when(chromaDb.count("id-projet")).thenReturn(8);
        when(fts.indexedCount("collection_projet")).thenReturn(0);

        service(null).reconcile();

        verify(fts).rebuildCollection("collection_projet");
        verify(fts, never()).rebuildCollection(DEFAULT_COLLECTION);
    }

    @Test
    void reconcile_chromaUnavailableForOneCollection_othersStillChecked() {
        when(fileRepo.findDistinctCollectionNames()).thenReturn(List.of("collection_cassee"));
        when(fileRepo.sumChunks()).thenReturn(0L);
        when(chromaDb.getOrCreateCollection(DEFAULT_COLLECTION))
                .thenThrow(new RuntimeException("chroma indisponible"));
        when(chromaDb.getOrCreateCollection("collection_cassee")).thenReturn("id-cassee");
        when(chromaDb.count("id-cassee")).thenReturn(3);
        when(fts.indexedCount("collection_cassee")).thenReturn(0);

        // L'échec de la collection par défaut ne doit pas empêcher les autres vérifications.
        service(null).reconcile();

        verify(fts).rebuildCollection("collection_cassee");
    }
}
