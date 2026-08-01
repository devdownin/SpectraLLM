package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.QueryRequest;
import fr.spectra.dto.ServiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Un reranker <b>présent mais inutilisable</b> doit se comporter comme un reranker absent.
 *
 * <p><b>Pourquoi ces tests existent.</b> Le reranking est passé actif par défaut
 * ({@code spectra.reranker.enabled=true}, décision D1/option (b) du plan de migration). Or le
 * moteur ONNX enregistre son bean même quand l'artefact est absent — délibérément, pour que
 * {@code /api/status} publie la cause plutôt que de taire la panne. {@code RagService} décidait
 * d'utiliser le reranker sur la seule <i>présence</i> du bean.
 *
 * <p>Combinés, ces deux choix produisaient une régression permanente sur toute installation
 * sans artefact : à <b>chaque</b> requête, sur-extraction de {@code topCandidates} candidats
 * (20 au lieu de {@code maxContextChunks}) auprès de ChromaDB, échec du reranking, troncature,
 * et un avertissement journalisé. Le résultat restait correct — c'est précisément ce qui rendait
 * le défaut coûteux et invisible.
 *
 * <p>Ces tests figent la propriété qui rend l'activation par défaut sûre. Sans elle, l'option (b)
 * serait une régression déguisée en fonctionnalité.
 *
 * <p><b>Pas de {@code @Nested} ici, délibérément.</b> Surefire rapporte alors « Tests run: 0 »
 * pour la classe englobante, ce que {@code scripts/check-test-reports.sh} traite — à juste
 * titre — comme une classe qui n'exécute rien. Le garde-fou ne peut pas distinguer ce cas d'un
 * {@code assumeTrue} avorté dans un {@code @BeforeAll}, et c'est précisément ce qui fait sa
 * valeur : il ne se laisse pas convaincre. Les sept tests étaient d'ailleurs invisibles au
 * décompte du dépôt (978 rapportés contre 985 exécutés).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagServiceRerankerAvailabilityTest {

    private static final int MAX_CONTEXT_CHUNKS = 5;
    private static final int TOP_CANDIDATES = 20;

    @Mock private ChromaDbClient chromaDbClient;
    @Mock private EmbeddingService embeddingService;
    @Mock private LlmChatClient llmChatClient;
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
        // Format « listes de listes » de ChromaDB, comme les autres tests de RagService.
        when(chromaDbClient.query(anyString(), anyList(), anyInt())).thenReturn(Map.of(
                "documents", List.of(List.of("chunk-a", "chunk-b")),
                "metadatas", List.of(List.of(Map.of("sourceFile", "doc.pdf"),
                                             Map.of("sourceFile", "doc.pdf"))),
                "distances", List.of(List.of(0.1, 0.2))));
    }

    /** Reranker dont l'artefact n'a pas pu être chargé : présent comme bean, inutilisable. */
    private static RerankerClient unavailableReranker() {
        RerankerClient client = mock(RerankerClient.class);
        lenient().when(client.isAvailable()).thenReturn(false);
        lenient().when(client.checkHealth()).thenReturn(new ServiceStatus(
                "reranker", "/data/models/reranker", false, "unavailable", 0,
                Map.of("engine", "onnx", "error", "modèle de reranking introuvable")));
        return client;
    }

    /** Reranker opérationnel : renvoie un classement inversé, observable dans le contexte. */
    private static RerankerClient availableReranker() {
        RerankerClient client = mock(RerankerClient.class);
        lenient().when(client.isAvailable()).thenReturn(true);
        lenient().when(client.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new RerankerClient.RankedResult(1, 0.9f),
                                    new RerankerClient.RankedResult(0, 0.2f)));
        return client;
    }

    private RagService ragService(Optional<RerankerClient> reranker) {
        ActiveModelProfileService profiles = mock(ActiveModelProfileService.class);
        lenient().when(profiles.current())
                .thenReturn(ActiveModelProfileService.ModelProfile.DEFAULT);
        return new RagService(
                chromaDbClient, embeddingService, llmChatClient,
                reranker, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                profiles, props, new ObjectMapper(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private static QueryRequest request() {
        return new QueryRequest("Quelles normes ?", MAX_CONTEXT_CHUNKS, TOP_CANDIDATES,
                null, null, null, null, true);
    }

    // ── Moteur non chargé (artefact absent) ──────────────────────────────────

    @Test
    @DisplayName("moteur non chargé : n'est jamais appelé — pas d'échec par requête")
    void neverInvoked() {
        RerankerClient reranker = unavailableReranker();

        ragService(Optional.of(reranker)).retrieveContext(request(), "Quelles normes ?", null);

        verify(reranker, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    @DisplayName("moteur non chargé : pas de sur-extraction de candidats auprès de ChromaDB")
    void doesNotOverFetchCandidates() {
        // Le cœur du défaut : retrieveCount vaut max(topCandidates, maxContextChunks)
        // quand le reranking est réputé actif. Sans reranker utilisable, ChromaDB ne doit
        // être interrogé que pour maxContextChunks — 5, et non 20.
        ragService(Optional.of(unavailableReranker()))
                .retrieveContext(request(), "Quelles normes ?", null);

        verify(chromaDbClient).query(anyString(), anyList(), eq(MAX_CONTEXT_CHUNKS));
        verify(chromaDbClient, never()).query(anyString(), anyList(), eq(TOP_CANDIDATES));
    }

    @Test
    @DisplayName("moteur non chargé : rerankApplied=false, comme une installation sans reranking")
    void reportsRerankNotApplied() {
        RagService.RagContext ctx = ragService(Optional.of(unavailableReranker()))
                .retrieveContext(request(), "Quelles normes ?", null);

        assertThat(ctx.rerankApplied()).isFalse();
    }

    @Test
    @DisplayName("moteur non chargé : n'est pas annoncé comme capacité disponible")
    void notAdvertisedAsAvailableModule() {
        // Sinon l'interface proposerait un reranking que le backend ne peut pas rendre.
        assertThat(ragService(Optional.of(unavailableReranker())).moduleAvailability())
                .containsEntry("rerank", false);
    }

    // ── Moteur chargé ────────────────────────────────────────────────────────

    @Test
    @DisplayName("moteur chargé : est utilisé, et la sur-extraction reprend son sens")
    void invokedAndOverFetches() {
        RerankerClient reranker = availableReranker();

        RagService.RagContext ctx = ragService(Optional.of(reranker))
                .retrieveContext(request(), "Quelles normes ?", null);

        verify(chromaDbClient).query(anyString(), anyList(), eq(TOP_CANDIDATES));
        verify(reranker).rerank(anyString(), anyList(), eq(MAX_CONTEXT_CHUNKS));
        assertThat(ctx.rerankApplied()).isTrue();
    }

    @Test
    @DisplayName("moteur chargé : est annoncé comme capacité disponible")
    void advertisedAsAvailableModule() {
        assertThat(ragService(Optional.of(availableReranker())).moduleAvailability())
                .containsEntry("rerank", true);
    }

    @Test
    @DisplayName("isAvailable() vaut true par défaut : une implémentation distante reste tentée")
    void remoteImplementationsStayOptimisticByDefault() {
        // Le client HTTP ne peut pas savoir sans appel réseau si le service répond ; il doit
        // donc être tenté puis échouer, comme avant. Ne pas régresser vers un défaut « false »
        // qui désactiverait silencieusement le moteur http.
        RerankerClient remote = new RerankerClient() {
            @Override public ServiceStatus checkHealth() {
                return new ServiceStatus("reranker", "http://reranker:8000", true, "ok", 0, Map.of());
            }
            @Override public List<RankedResult> rerank(String q, List<String> docs, int topN) {
                return List.of();
            }
        };

        assertThat(remote.isAvailable()).isTrue();
    }
}
