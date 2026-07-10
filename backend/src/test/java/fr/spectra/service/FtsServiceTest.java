package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.model.TextChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de FtsService — gestion des indices BM25 par collection.
 * ChromaDbClient est mocké ; les indices sont manipulés via indexChunks/removeBySource.
 */
class FtsServiceTest {

    private FtsService ftsService;

    @BeforeEach
    void setUp() {
        ChromaDbClient chromaDb = mock(ChromaDbClient.class);
        // Évite que rebuildDefaultIndexAsync explose au démarrage
        when(chromaDb.getOrCreateCollection("spectra_documents"))
                .thenThrow(new RuntimeException("ChromaDB absent en test"));

        SpectraProperties.ChromaDbProperties chromaProps =
                new SpectraProperties.ChromaDbProperties("http://chroma:8000", "spectra_documents");
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.chromadb()).thenReturn(chromaProps);

        ftsService = new FtsService(chromaDb, props);
    }

    // ── indexChunks ───────────────────────────────────────────────────────────

    @Test
    void indexChunks_singleChunk_indexedCountIsOne() {
        ftsService.indexChunks(List.of(chunk("c1", "contenu du document", "doc.txt")), "coll1");
        assertThat(ftsService.indexedCount("coll1")).isEqualTo(1);
    }

    @Test
    void indexChunks_multipleBatches_countCumulates() {
        ftsService.indexChunks(List.of(
                chunk("c1", "premier chunk", "a.txt"),
                chunk("c2", "deuxième chunk", "a.txt")), "coll1");
        ftsService.indexChunks(List.of(
                chunk("c3", "troisième chunk", "b.txt")), "coll1");

        assertThat(ftsService.indexedCount("coll1")).isEqualTo(3);
    }

    @Test
    void indexChunks_differentCollections_separateIndices() {
        ftsService.indexChunks(List.of(chunk("c1", "texte A", "a.txt")), "collA");
        ftsService.indexChunks(List.of(chunk("c2", "texte B", "b.txt")), "collB");

        assertThat(ftsService.indexedCount("collA")).isEqualTo(1);
        assertThat(ftsService.indexedCount("collB")).isEqualTo(1);
    }

    // ── search ────────────────────────────────────────────────────────────────

    @Test
    void search_emptyIndex_returnsEmptyList() {
        assertThat(ftsService.search("query", "coll1", 5)).isEmpty();
    }

    @Test
    void search_afterIndexing_findsChunk() {
        ftsService.indexChunks(List.of(
                chunk("c1", "spectra plateforme rag intelligence artificielle", "doc.txt")), "coll1");

        List<BM25Index.ScoredDoc> results = ftsService.search("spectra", "coll1", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("c1");
    }

    @Test
    void search_topNHonoured() {
        for (int i = 0; i < 10; i++) {
            ftsService.indexChunks(List.of(chunk("c" + i, "document rag " + i, "f.txt")), "coll1");
        }
        assertThat(ftsService.search("document", "coll1", 3)).hasSize(3);
    }

    @Test
    void search_unknownCollection_returnsEmptyList() {
        assertThat(ftsService.search("query", "inexistante", 5)).isEmpty();
    }

    // ── removeBySource ────────────────────────────────────────────────────────

    @Test
    void removeBySource_removesChunksOfSource() {
        ftsService.indexChunks(List.of(
                chunk("c1", "doc source A premier", "sourceA.txt"),
                chunk("c2", "doc source A second", "sourceA.txt"),
                chunk("c3", "doc source B", "sourceB.txt")), "coll1");

        ftsService.removeBySource("sourceA.txt", "coll1");

        assertThat(ftsService.indexedCount("coll1")).isEqualTo(1);
        assertThat(ftsService.search("sourceA", "coll1", 5)).isEmpty();
    }

    @Test
    void removeBySource_unknownCollection_noException() {
        // Ne doit pas lever d'exception
        ftsService.removeBySource("fichier.txt", "inexistante");
    }

    // ── getStatus ─────────────────────────────────────────────────────────────

    @Test
    void getStatus_emptyCollection_notReady() {
        FtsService.FtsStatus status = ftsService.getStatus("vide");
        assertThat(status.ready()).isFalse();
        assertThat(status.indexedChunks()).isZero();
    }

    @Test
    void getStatus_afterIndexing_ready() {
        ftsService.indexChunks(List.of(chunk("c1", "texte indexé", "doc.txt")), "coll1");
        FtsService.FtsStatus status = ftsService.getStatus("coll1");

        assertThat(status.ready()).isTrue();
        assertThat(status.indexedChunks()).isEqualTo(1);
        assertThat(status.collections()).isEqualTo("coll1");
    }

    @Test
    void getAggregatedStatus_multipleCollections_sumsAll() {
        ftsService.indexChunks(List.of(chunk("c1", "texte A", "a.txt")), "collA");
        ftsService.indexChunks(List.of(
                chunk("c2", "texte B", "b.txt"),
                chunk("c3", "texte C", "c.txt")), "collB");

        FtsService.FtsStatus agg = ftsService.getAggregatedStatus();
        assertThat(agg.indexedChunks()).isEqualTo(3);
        assertThat(agg.ready()).isTrue();
    }

    @Test
    void getAggregatedStatus_noCollections_notReady() {
        FtsService.FtsStatus agg = ftsService.getAggregatedStatus();
        assertThat(agg.ready()).isFalse();
        assertThat(agg.indexedChunks()).isZero();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private TextChunk chunk(String id, String text, String sourceFile) {
        return new TextChunk(id, text, 0, sourceFile, Map.of());
    }
}
