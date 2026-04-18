package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de HybridSearchService — fusion RRF (vecteurs + BM25).
 * ChromaDbClient et FtsService sont mockés.
 */
class HybridSearchServiceTest {

    private ChromaDbClient chromaDb;
    private FtsService ftsService;
    private HybridSearchService hybridSearch;

    private static final List<Float> QUERY_EMBEDDING = List.of(0.1f, 0.2f, 0.3f);
    private static final String COLL_ID   = "coll-id-123";
    private static final String COLL_NAME = "test-collection";

    @BeforeEach
    void setUp() {
        chromaDb  = mock(ChromaDbClient.class);
        ftsService = mock(FtsService.class);

        SpectraProperties.HybridSearchProperties hybridProps =
                new SpectraProperties.HybridSearchProperties(true, 20, 1.0f);
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.hybridSearch()).thenReturn(hybridProps);

        hybridSearch = new HybridSearchService(chromaDb, ftsService, props);
    }

    // ── Cas nominal ───────────────────────────────────────────────────────────

    @Test
    void search_vectorOnly_noFtsResults_returnsVectorChunks() {
        stubVector(List.of("d1", "d2"), List.of("texte un", "texte deux"),
                List.of(0.1, 0.3), List.of(Map.of("sourceFile", "a.txt"), Map.of("sourceFile", "b.txt")));
        when(ftsService.search(anyString(), anyString(), anyInt())).thenReturn(List.of());

        List<HybridSearchService.HybridChunk> results =
                hybridSearch.search("query", QUERY_EMBEDDING, COLL_ID, COLL_NAME, 5);

        assertThat(results).hasSize(2);
        // vector-only → bm25Score == 0
        assertThat(results).allMatch(c -> c.bm25Score() == 0f);
    }

    @Test
    void search_bm25Only_noVectorResults_returnsBm25Chunks() {
        stubVector(List.of(), List.of(), List.of(), List.of());
        when(ftsService.search(anyString(), anyString(), anyInt())).thenReturn(List.of(
                new BM25Index.ScoredDoc("d1", "texte bm25", "src.txt", 3.5f),
                new BM25Index.ScoredDoc("d2", "autre bm25", "src.txt", 2.0f)
        ));

        List<HybridSearchService.HybridChunk> results =
                hybridSearch.search("query", QUERY_EMBEDDING, COLL_ID, COLL_NAME, 5);

        assertThat(results).hasSize(2);
        // bm25-only → vectorDistance == 1.0
        assertThat(results).allMatch(c -> c.vectorDistance() >= 1.0);
    }

    @Test
    void search_bothSources_returnsRrfFusion() {
        stubVector(List.of("d1", "d2"), List.of("vecteur un", "vecteur deux"),
                List.of(0.1, 0.2), List.of(Map.of("sourceFile", "a.txt"), Map.of("sourceFile", "b.txt")));
        when(ftsService.search(anyString(), anyString(), anyInt())).thenReturn(List.of(
                new BM25Index.ScoredDoc("d1", "vecteur un", "a.txt", 4.0f),  // overlap avec vector
                new BM25Index.ScoredDoc("d3", "bm25 exclusif", "c.txt", 2.0f)
        ));

        List<HybridSearchService.HybridChunk> results =
                hybridSearch.search("query", QUERY_EMBEDDING, COLL_ID, COLL_NAME, 10);

        // 3 docs distincts : d1 (les deux), d2 (vec only), d3 (bm25 only)
        assertThat(results).hasSize(3);
    }

    @Test
    void search_rrfScoresDescending() {
        // d1 présent dans les deux → RRF plus élevé que d2/d3 présents dans un seul
        stubVector(List.of("d1", "d2"), List.of("texte", "texte"),
                List.of(0.05, 0.15), List.of(Map.of("sourceFile", "f.txt"), Map.of("sourceFile", "f.txt")));
        when(ftsService.search(anyString(), anyString(), anyInt())).thenReturn(List.of(
                new BM25Index.ScoredDoc("d1", "texte", "f.txt", 5.0f),
                new BM25Index.ScoredDoc("d3", "autre", "g.txt", 3.0f)
        ));

        List<HybridSearchService.HybridChunk> results =
                hybridSearch.search("query", QUERY_EMBEDDING, COLL_ID, COLL_NAME, 10);

        // d1 doit être en tête (présent dans les deux listes)
        assertThat(results.get(0).id()).isEqualTo("d1");
        // Les scores RRF doivent être décroissants
        for (int i = 0; i < results.size() - 1; i++) {
            assertThat(results.get(i).rrfScore())
                    .isGreaterThanOrEqualTo(results.get(i + 1).rrfScore());
        }
    }

    @Test
    void search_topCandidatesLimitsResults() {
        stubVector(List.of("d1", "d2", "d3", "d4", "d5"),
                List.of("t1", "t2", "t3", "t4", "t5"),
                List.of(0.1, 0.2, 0.3, 0.4, 0.5),
                List.of(Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
        when(ftsService.search(anyString(), anyString(), anyInt())).thenReturn(List.of());

        List<HybridSearchService.HybridChunk> results =
                hybridSearch.search("query", QUERY_EMBEDDING, COLL_ID, COLL_NAME, 3);

        assertThat(results).hasSize(3);
    }

    @Test
    void search_emptyBothSources_returnsEmptyList() {
        stubVector(List.of(), List.of(), List.of(), List.of());
        when(ftsService.search(anyString(), anyString(), anyInt())).thenReturn(List.of());

        List<HybridSearchService.HybridChunk> results =
                hybridSearch.search("query", QUERY_EMBEDDING, COLL_ID, COLL_NAME, 5);

        assertThat(results).isEmpty();
    }

    @Test
    void search_rrfScorePositiveForAllChunks() {
        stubVector(List.of("d1"), List.of("texte"), List.of(0.2),
                List.of(Map.of("sourceFile", "f.txt")));
        when(ftsService.search(anyString(), anyString(), anyInt())).thenReturn(List.of());

        List<HybridSearchService.HybridChunk> results =
                hybridSearch.search("query", QUERY_EMBEDDING, COLL_ID, COLL_NAME, 5);

        assertThat(results).allMatch(c -> c.rrfScore() > 0.0);
    }

    // ── Champs de HybridChunk ─────────────────────────────────────────────────

    @Test
    void search_hybridChunk_hasAllFields() {
        stubVector(List.of("d1"), List.of("texte du chunk"),
                List.of(0.15), List.of(Map.of("sourceFile", "mon_fichier.txt")));
        when(ftsService.search(anyString(), anyString(), anyInt())).thenReturn(List.of(
                new BM25Index.ScoredDoc("d1", "texte du chunk", "mon_fichier.txt", 2.5f)
        ));

        HybridSearchService.HybridChunk chunk =
                hybridSearch.search("query", QUERY_EMBEDDING, COLL_ID, COLL_NAME, 5).get(0);

        assertThat(chunk.id()).isEqualTo("d1");
        assertThat(chunk.text()).isEqualTo("texte du chunk");
        assertThat(chunk.sourceFile()).isEqualTo("mon_fichier.txt");
        assertThat(chunk.vectorDistance()).isEqualTo(0.15);
        assertThat(chunk.bm25Score()).isEqualTo(2.5f);
        assertThat(chunk.rrfScore()).isGreaterThan(0.0);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void stubVector(List<String> ids, List<String> docs,
                            List<Double> dists, List<Map<String, String>> metas) {
        Map<String, Object> result = Map.of(
                "ids",       List.of(ids),
                "documents", List.of(docs),
                "distances", List.of(dists),
                "metadatas", List.of(metas)
        );
        when(chromaDb.query(eq(COLL_ID), any(), anyInt())).thenReturn(result);
    }
}
