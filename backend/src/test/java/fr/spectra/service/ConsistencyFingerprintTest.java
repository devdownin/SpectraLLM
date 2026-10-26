package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.persistence.IngestedFileRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class ConsistencyFingerprintTest {
    @Test
    void equalCountsStillRebuildWhenContentIdentityDiffers() {
        var repository = mock(IngestedFileRepository.class);
        when(repository.findDistinctCollectionNames()).thenReturn(List.of());
        var chroma = mock(ChromaDbClient.class);
        when(chroma.getOrCreateCollection("spectra_documents")).thenReturn("id");
        when(chroma.count("id")).thenReturn(1);
        var fts = mock(FtsService.class);
        when(fts.indexedCount("spectra_documents")).thenReturn(1);
        when(fts.matchesChroma("spectra_documents")).thenReturn(false);
        var properties = mock(SpectraProperties.class);
        try (var registry = new SimpleMeterRegistry()) {
            new ConsistencyReconciliationService(repository, chroma, fts, properties, registry).reconcile();
        }
        verify(fts).matchesChroma("spectra_documents");
        verify(fts).rebuildCollection("spectra_documents");
    }
}
