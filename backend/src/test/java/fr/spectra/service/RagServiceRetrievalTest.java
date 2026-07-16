package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.QueryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests de la phase retrieval de {@link RagService} :
 *
 * <ul>
 *   <li><b>Multi-query + hybrid</b> : la fusion doit préserver le classement RRF de la
 *       recherche hybride (somme des scores RRF entre variantes, tri décroissant).
 *       Régression : le tri par distance vectorielle reléguait systématiquement en queue
 *       les chunks issus du BM25 seul (distance sentinelle 1.0).</li>
 *   <li><b>Long-context</b> : le chargement intégral du corpus est borné par un budget de
 *       tokens ; au-delà, retour au retrieval vectoriel standard.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagServiceRetrievalTest {

    @Mock private ChromaDbClient chromaDbClient;
    @Mock private EmbeddingService embeddingService;
    @Mock private LlmChatClient llmChatClient;
    @Mock private HybridSearchService hybridSearchService;
    @Mock private MultiQueryService multiQueryService;
    @Mock private SpectraProperties props;
    @Mock private SpectraProperties.PipelineProperties pipeline;

    @BeforeEach
    void setUp() {
        when(props.pipeline()).thenReturn(pipeline);
        when(pipeline.generationTimeoutSeconds()).thenReturn(30);
        when(props.chromadb()).thenReturn(null);
        when(props.longContextRag()).thenReturn(null);
        when(props.semanticDedup()).thenReturn(null);

        when(chromaDbClient.getOrCreateCollection(anyString())).thenReturn("col-1");
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));
    }

    private RagService ragService(Optional<HybridSearchService> hybrid,
                                  Optional<MultiQueryService> multiQuery) {
        return new RagService(
                chromaDbClient, embeddingService, llmChatClient,
                Optional.empty(), hybrid, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), multiQuery,
                props, new ObjectMapper());
    }

    private static QueryRequest request() {
        return new QueryRequest("Quelles normes ?", null, null, null, null, null, null, true);
    }

    private static HybridSearchService.HybridChunk chunk(String id, String text,
                                                         double dist, float bm25, double rrf) {
        return new HybridSearchService.HybridChunk(id, text, "doc.pdf", dist, bm25, rrf);
    }

    // ── B1 : fusion multi-query préservant le classement RRF ─────────────────

    @Test
    void multiQueryHybrid_mergeSortsByRrfScore_notByVectorDistance() {
        when(multiQueryService.generateQueries(anyString()))
                .thenReturn(List.of("Quelles normes ?", "variante"));

        // Variante 1 : chunkB trouvé par BM25 seul (distance sentinelle 1.0) mais
        // classé PREMIER par le RRF ; chunkA vectoriel proche mais RRF plus faible.
        when(hybridSearchService.search(eq("Quelles normes ?"), anyList(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(
                        chunk("b", "chunk-bm25-only", 1.0, 8.5f, 0.030),
                        chunk("a", "chunk-vectoriel", 0.1, 0.0f, 0.016)));

        // Variante 2 : chunkA retrouvé une seconde fois (sa contribution RRF s'additionne :
        // 0.016 + 0.016 = 0.032 > 0.030), plus un chunkC faible.
        when(hybridSearchService.search(eq("variante"), anyList(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(
                        chunk("a", "chunk-vectoriel", 0.3, 0.0f, 0.016),
                        chunk("c", "chunk-faible", 0.4, 0.0f, 0.010)));

        RagService.RagContext ctx = ragService(Optional.of(hybridSearchService), Optional.of(multiQueryService))
                .retrieveContext(request());

        // Tri attendu par RRF sommé : A (0.032), B (0.030), C (0.010).
        // L'ancien tri par distance donnait A (0.1), C (0.4), B (1.0) — B relégué en queue.
        assertThat(ctx.contextChunks())
                .containsExactly("chunk-vectoriel", "chunk-bm25-only", "chunk-faible");
        assertThat(ctx.multiQueryApplied()).isTrue();
        assertThat(ctx.hybridApplied()).isTrue();
    }

    // ── B8 : budget de tokens du Long-Context RAG ────────────────────────────

    @Test
    void longContext_corpusOverTokenBudget_fallsBackToStandardRetrieval() {
        // Budget de 50 tokens (~200 caractères) ; corpus de ~1000 caractères (~250 tokens).
        when(props.longContextRag()).thenReturn(
                new SpectraProperties.LongContextRagProperties(true, 100, 50));
        when(chromaDbClient.count("col-1")).thenReturn(2);
        String bigDoc = "x".repeat(500);
        when(chromaDbClient.getAllDocuments("col-1")).thenReturn(Map.of(
                "documents", List.of(bigDoc, bigDoc),
                "metadatas", List.of(Map.of("sourceFile", "a.txt"), Map.of("sourceFile", "b.txt"))));
        when(chromaDbClient.query(eq("col-1"), anyList(), anyInt())).thenReturn(Map.of(
                "documents", List.of(List.of("chunk-retrieval")),
                "metadatas", List.of(List.of(Map.of("sourceFile", "a.txt"))),
                "distances", List.of(List.of(0.2))));

        RagService.RagContext ctx = ragService(Optional.empty(), Optional.empty())
                .retrieveContext(request());

        assertThat(ctx.longContextApplied()).isFalse();
        assertThat(ctx.contextChunks()).containsExactly("chunk-retrieval");
        verify(chromaDbClient).query(eq("col-1"), anyList(), anyInt());
    }

    @Test
    void longContext_corpusWithinTokenBudget_loadsFullCollection() {
        when(props.longContextRag()).thenReturn(
                new SpectraProperties.LongContextRagProperties(true, 100, 3000));
        when(chromaDbClient.count("col-1")).thenReturn(2);
        when(chromaDbClient.getAllDocuments("col-1")).thenReturn(Map.of(
                "documents", List.of("petit chunk 1", "petit chunk 2"),
                "metadatas", List.of(Map.of("sourceFile", "a.txt"), Map.of("sourceFile", "b.txt"))));

        RagService.RagContext ctx = ragService(Optional.empty(), Optional.empty())
                .retrieveContext(request());

        assertThat(ctx.longContextApplied()).isTrue();
        assertThat(ctx.contextChunks()).containsExactly("petit chunk 1", "petit chunk 2");
        verify(chromaDbClient, never()).query(anyString(), any(), anyInt());
    }
}
