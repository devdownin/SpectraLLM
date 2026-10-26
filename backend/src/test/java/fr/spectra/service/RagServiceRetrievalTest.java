package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.QueryRequest;
import fr.spectra.dto.QueryResponse;
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
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        return ragService(hybrid, multiQuery, Optional.empty());
    }

    private RagService ragService(Optional<HybridSearchService> hybrid,
                                  Optional<MultiQueryService> multiQuery,
                                  Optional<CorrectiveRagService> corrective) {
        return new RagService(
                chromaDbClient, embeddingService, llmChatClient,
                Optional.empty(), hybrid, Optional.empty(),
                Optional.empty(), corrective, Optional.empty(),
                Optional.empty(), Optional.empty(), multiQuery,
                defaultProfiles(), props, new ObjectMapper(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /**
     * Profil de modèle actif neutre : persona canonique Spectra et défauts 0.7/0.9 — le
     * comportement de référence de ces tests de retrieval.
     */
    private static ActiveModelProfileService defaultProfiles() {
        ActiveModelProfileService profiles = org.mockito.Mockito.mock(ActiveModelProfileService.class);
        org.mockito.Mockito.lenient().when(profiles.current())
                .thenReturn(ActiveModelProfileService.ModelProfile.DEFAULT);
        return profiles;
    }

    private static QueryRequest request() {
        return new QueryRequest("Quelles normes ?", null, null, null, null, null, null, true);
    }

    private static HybridSearchService.HybridChunk chunk(String id, String text,
                                                         double dist, float bm25, double rrf) {
        return new HybridSearchService.HybridChunk(id, text, "doc.pdf", dist, bm25, rrf);
    }

    @Test
    void maintenanceBlocksRetrievalBeforeQueryEmbedding() {
        var guard = org.mockito.Mockito.mock(CollectionMaintenanceGuard.class);
        when(guard.acquire(anyString())).thenThrow(new IllegalStateException("migration in progress"));
        var service = ragService(Optional.empty(), Optional.empty());
        service.setCollectionMaintenanceGuard(guard);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.retrieveContext(request()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("migration");
        verify(embeddingService, never()).embed(anyString());
        verify(chromaDbClient, never()).getOrCreateCollection(anyString());
    }

    // ── B1 : fusion multi-query préservant le classement RRF ─────────────────

    @Test
    void multiQueryHybrid_mergeSortsByRrfScore_notByVectorDistance() {
        when(multiQueryService.generateQueries(anyString()))
                .thenReturn(List.of("Quelles normes ?", "variante"));

        // Variante 1 : chunkB trouvé par BM25 seul (distance sentinelle 1.0) mais
        // classé PREMIER par le RRF ; chunkA vectoriel proche mais RRF plus faible.
        when(hybridSearchService.search(eq("Quelles normes ?"), anyList(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(List.of(
                        chunk("b", "chunk-bm25-only", 1.0, 8.5f, 0.030),
                        chunk("a", "chunk-vectoriel", 0.1, 0.0f, 0.016)));

        // Variante 2 : chunkA retrouvé une seconde fois (sa contribution RRF s'additionne :
        // 0.016 + 0.016 = 0.032 > 0.030), plus un chunkC faible.
        when(hybridSearchService.search(eq("variante"), anyList(), anyString(), anyString(), anyInt(), any()))
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

    // ── B3 : Corrective RAG — retrieval complémentaire après reformulation ───

    private static Map<String, Object> vectorResult(String chunkText) {
        return Map.of(
                "documents", List.of(List.of(chunkText)),
                "metadatas", List.of(List.of(Map.of("sourceFile", "doc.txt"))),
                "distances", List.of(List.of(0.2)));
    }

    @Test
    void corrective_allChunksIrrelevant_reformulatesAndRetrievesComplementary() {
        when(props.correctiveRag()).thenReturn(
                new SpectraProperties.CorrectiveRagProperties(true, 1));
        CorrectiveRagService corrective = new CorrectiveRagService(llmChatClient, props);

        // Retrieval initial → chunk hors-sujet ; retrieval complémentaire → chunk pertinent.
        when(chromaDbClient.query(eq("col-1"), anyList(), anyInt()))
                .thenReturn(vectorResult("chunk hors-sujet"), vectorResult("chunk pertinent"));
        // Appels LLM 2-args, dans l'ordre : grading initial (tout IRRELEVANT) →
        // reformulation → grading des chunks complémentaires (RELEVANT).
        when(llmChatClient.chat(anyString(), anyString()))
                .thenReturn("1: IRRELEVANT", "Question reformulée ?", "1: RELEVANT");
        // Génération finale (4-args, avec temperature/topP).
        when(llmChatClient.chat(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn("Réponse finale");

        QueryResponse resp = ragService(Optional.empty(), Optional.empty(), Optional.of(corrective))
                .query(request());

        assertThat(resp.answer()).isEqualTo("Réponse finale");
        assertThat(resp.correctiveApplied()).isTrue();
        assertThat(resp.sources()).hasSize(1);
        assertThat(resp.sources().get(0).text()).contains("chunk pertinent");
        // Deux retrievals vectoriels : initial + complémentaire.
        verify(chromaDbClient, times(2)).query(eq("col-1"), anyList(), anyInt());
    }

    @Test
    void corrective_enoughRelevantChunks_noComplementaryRetrieval() {
        when(props.correctiveRag()).thenReturn(
                new SpectraProperties.CorrectiveRagProperties(true, 1));
        CorrectiveRagService corrective = new CorrectiveRagService(llmChatClient, props);

        when(chromaDbClient.query(eq("col-1"), anyList(), anyInt()))
                .thenReturn(vectorResult("chunk pertinent"));
        when(llmChatClient.chat(anyString(), anyString())).thenReturn("1: RELEVANT");
        when(llmChatClient.chat(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn("Réponse finale");

        QueryResponse resp = ragService(Optional.empty(), Optional.empty(), Optional.of(corrective))
                .query(request());

        assertThat(resp.answer()).isEqualTo("Réponse finale");
        // Rien filtré, rien complété → l'étape corrective n'a pas modifié le contexte.
        assertThat(resp.correctiveApplied()).isFalse();
        verify(chromaDbClient, times(1)).query(eq("col-1"), anyList(), anyInt());
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

    // ── Fusion multi-query RRF en vectoriel pur ──────────────────────────────

    private static Map<String, Object> vectorResults(List<String> texts, List<Double> distances) {
        return Map.of(
                "documents", List.of(texts),
                "metadatas", List.of(texts.stream().map(t -> Map.of("sourceFile", "doc.txt")).toList()),
                "distances", List.of(distances));
    }

    @Test
    void multiQueryVector_mergeRewardsChunksFoundBySeveralVariants() {
        when(multiQueryService.generateQueries(anyString()))
                .thenReturn(List.of("Quelles normes ?", "variante"));
        when(embeddingService.embed("Quelles normes ?")).thenReturn(List.of(1f));
        when(embeddingService.embed("variante")).thenReturn(List.of(2f));
        when(chromaDbClient.query(eq("col-1"), eq(List.of(1f)), anyInt()))
                .thenReturn(vectorResults(List.of("x", "y"), List.of(0.10, 0.20)));
        when(chromaDbClient.query(eq("col-1"), eq(List.of(2f)), anyInt()))
                .thenReturn(vectorResults(List.of("z", "y"), List.of(0.05, 0.30)));

        RagService.RagContext ctx = ragService(Optional.empty(), Optional.of(multiQueryService))
                .retrieveContext(request());

        // RRF : y = 2/(60+2) > x = z = 1/(60+1). Le tri par meilleure distance donnait z, x, y.
        assertThat(ctx.contextChunks()).containsExactly("y", "x", "z");
    }

    // ── Préfixes d'instruction d'embedding ───────────────────────────────────

    @Test
    void queryEmbedding_usesPromptSchemeStampedOnCollection() {
        when(chromaDbClient.embeddingContract(anyString())).thenReturn(
                EmbeddingContract.current("nomic-embed-text", 2048));
        when(chromaDbClient.query(eq("col-1"), anyList(), anyInt())).thenReturn(vectorResult("chunk"));

        ragService(Optional.empty(), Optional.empty()).retrieveContext(request());

        verify(embeddingService).embed("search_query: Quelles normes ?");
    }

    // ── Seuil de pertinence du reranker ──────────────────────────────────────

    private RagService ragServiceWithReranker(RerankerClient reranker, Float minScore) {
        when(props.reranker()).thenReturn(new SpectraProperties.RerankerProperties(
                true, null, null, null, 20, "onnx", null, minScore));
        when(reranker.isAvailable()).thenReturn(true);
        return new RagService(
                chromaDbClient, embeddingService, llmChatClient,
                Optional.of(reranker), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                defaultProfiles(), props, new ObjectMapper(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    void rerankThreshold_dropsChunksScoredBelowMinScore() {
        RerankerClient reranker = org.mockito.Mockito.mock(RerankerClient.class);
        when(chromaDbClient.query(eq("col-1"), anyList(), anyInt()))
                .thenReturn(vectorResults(List.of("pertinent", "hors-sujet"), List.of(0.2, 0.3)));
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(
                new RerankerClient.RankedResult(0, 0.92f), new RerankerClient.RankedResult(1, 0.04f)));

        RagService.RagContext ctx = ragServiceWithReranker(reranker, 0.2f).retrieveContext(request());

        assertThat(ctx.contextChunks()).containsExactly("pertinent");
        assertThat(ctx.rerankScores()).containsExactly(0.92f);
    }

    @Test
    void rerankThreshold_nothingRelevant_answersWithoutCallingTheLlm() {
        RerankerClient reranker = org.mockito.Mockito.mock(RerankerClient.class);
        when(chromaDbClient.query(eq("col-1"), anyList(), anyInt())).thenReturn(vectorResult("hors-sujet"));
        when(reranker.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new RerankerClient.RankedResult(0, 0.01f)));

        QueryResponse resp = ragServiceWithReranker(reranker, 0.2f).query(request());

        assertThat(resp.sources()).isEmpty();
        assertThat(resp.answer()).startsWith("Aucun document pertinent");
        verify(llmChatClient, never()).chat(anyString(), anyString(), anyFloat(), anyFloat());
    }

    @Test
    void rerankThreshold_unsetKeepsEveryRerankedChunk() {
        RerankerClient reranker = org.mockito.Mockito.mock(RerankerClient.class);
        when(chromaDbClient.query(eq("col-1"), anyList(), anyInt())).thenReturn(vectorResult("faible"));
        when(reranker.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new RerankerClient.RankedResult(0, 0.01f)));

        RagService.RagContext ctx = ragServiceWithReranker(reranker, null).retrieveContext(request());

        assertThat(ctx.contextChunks()).containsExactly("faible");
    }

    // ── A5 : expansion aux chunks voisins ────────────────────────────────────

    private static Map<String, Object> vectorResultWithMeta(String text, Map<String, String> meta) {
        return Map.of(
                "documents", List.of(List.of(text)),
                "metadatas", List.of(List.of(meta)),
                "distances", List.of(List.of(0.2)));
    }

    @Test
    void neighborExpansion_joinsAdjacentChunksWithoutDuplicatingTheOverlap() {
        when(props.rag()).thenReturn(new SpectraProperties.RagProperties(null, null, 1, 4000));
        when(chromaDbClient.query(eq("col-1"), anyList(), anyInt())).thenReturn(vectorResultWithMeta(
                "fin du recouvrement. Le chunk retenu.", Map.of("sourceFile", "a.pdf", "sha256", "shaA", "chunkIndex", "3")));
        when(chromaDbClient.getChunksByIndex("col-1", "sha256", "shaA", List.of(2, 4))).thenReturn(Map.of(
                2, "Le chunk précédent, fin du recouvrement.",
                4, "Le chunk suivant."));

        RagService.RagContext ctx = ragService(Optional.empty(), Optional.empty()).retrieveContext(request());

        assertThat(ctx.contextChunks()).containsExactly(
                "Le chunk précédent, fin du recouvrement. Le chunk retenu.\n\nLe chunk suivant.");
        assertThat(ctx.sources().getFirst().sourceFile()).isEqualTo("a.pdf");
    }

    @Test
    void neighborExpansionKeepsZipEntryIdentityWhenArchiveIndexesCollide() {
        when(props.rag()).thenReturn(new SpectraProperties.RagProperties(null, null, 1, 4000));
        when(chromaDbClient.query(eq("col-1"), anyList(), anyInt())).thenReturn(vectorResultWithMeta(
                "Passage de A.", Map.of("sourceFile", "archive.zip", "sha256", "archive-sha",
                        "zipEntry", "archive.zip/a.txt", "chunkIndex", "0")));
        when(chromaDbClient.getChunksByIndex("col-1", "sha256", "archive-sha", List.of(1), "archive.zip/a.txt"))
                .thenReturn(Map.of(1, "Suite de A."));
        // This old, archive-wide lookup would select an unrelated entry with the same index.
        when(chromaDbClient.getChunksByIndex("col-1", "sha256", "archive-sha", List.of(1)))
                .thenReturn(Map.of(1, "Contenu contradictoire de B."));

        RagService.RagContext ctx = ragService(Optional.empty(), Optional.empty()).retrieveContext(request());

        assertThat(ctx.contextChunks()).containsExactly("Passage de A.\n\nSuite de A.");
        verify(chromaDbClient, never()).getChunksByIndex("col-1", "sha256", "archive-sha", List.of(1));
    }

    @Test
    void neighborExpansionSkipsLegacyArchiveWithoutEntryProvenance() {
        when(props.rag()).thenReturn(new SpectraProperties.RagProperties(null, null, 1, 4000));
        when(chromaDbClient.query(eq("col-1"), anyList(), anyInt())).thenReturn(vectorResultWithMeta(
                "Passage historique.", Map.of("sourceFile", "ARCHIVE.ZIP", "sha256", "archive-sha", "chunkIndex", "0")));

        RagService.RagContext ctx = ragService(Optional.empty(), Optional.empty()).retrieveContext(request());

        assertThat(ctx.contextChunks()).containsExactly("Passage historique.");
        verify(chromaDbClient, never()).getChunksByIndex(anyString(), anyString(), anyString(), anyList());
        verify(chromaDbClient, never()).getChunksByIndex(anyString(), anyString(), anyString(), anyList(), anyString());
    }

    @Test
    void neighborExpansion_respectsTheSizeCapAndLegacyChunksWithoutIndex() {
        when(props.rag()).thenReturn(new SpectraProperties.RagProperties(null, null, 1, 30));
        when(chromaDbClient.query(eq("col-1"), anyList(), anyInt())).thenReturn(vectorResultWithMeta(
                "Chunk retenu.", Map.of("sourceFile", "a.pdf", "chunkIndex", "0")));
        when(chromaDbClient.getChunksByIndex("col-1", "sourceFile", "a.pdf", List.of(1)))
                .thenReturn(Map.of(1, "Un voisin bien trop long pour tenir dans le plafond."));

        RagService.RagContext ctx = ragService(Optional.empty(), Optional.empty()).retrieveContext(request());

        assertThat(ctx.contextChunks()).containsExactly("Chunk retenu.");
    }

    @Test
    void joinOverlapping_withoutCommonTextKeepsBothParts() {
        assertThat(RagService.joinOverlapping("Premier.", "Second.")).isEqualTo("Premier.\n\nSecond.");
    }

    // ── A7 : MMR sur embeddings ──────────────────────────────────────────────

    @Test
    void mmr_dropsSemanticNearDuplicates_andKeepsDiverseChunks() {
        when(props.semanticDedup()).thenReturn(new SpectraProperties.SemanticDedupProperties(true, 0.95, "mmr", 0.7));
        when(chromaDbClient.query(eq("col-1"), anyList(), anyInt()))
                .thenReturn(vectorResults(List.of("a", "a-bis", "b"), List.of(0.1, 0.2, 0.3)));
        when(embeddingService.embedBatch(List.of("a", "a-bis", "b"))).thenReturn(List.of(
                List.of(1f, 0f), List.of(0.99f, 0.01f), List.of(0f, 1f)));

        RagService.RagContext ctx = ragService(Optional.empty(), Optional.empty()).retrieveContext(request());

        assertThat(ctx.contextChunks()).containsExactly("a", "b");
        assertThat(ctx.semanticDedupApplied()).isTrue();
    }

    @Test
    void mmr_embeddingFailure_keepsTheContextUnchanged() {
        when(props.semanticDedup()).thenReturn(new SpectraProperties.SemanticDedupProperties(true, 0.95, "mmr", 0.7));
        when(chromaDbClient.query(eq("col-1"), anyList(), anyInt()))
                .thenReturn(vectorResults(List.of("a", "b"), List.of(0.1, 0.2)));
        when(embeddingService.embedBatch(anyList())).thenThrow(new RuntimeException("embed down"));

        RagService.RagContext ctx = ragService(Optional.empty(), Optional.empty()).retrieveContext(request());

        assertThat(ctx.contextChunks()).containsExactly("a", "b");
        assertThat(ctx.semanticDedupApplied()).isFalse();
    }
}
