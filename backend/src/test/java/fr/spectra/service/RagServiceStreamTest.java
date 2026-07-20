package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.QueryRequest;
import fr.spectra.dto.RagOverrides;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires du streaming SSE de RagService (#7 et #8).
 *
 * Cas couverts :
 *  - chemin direct (useRag=false) : sources → tokens → done
 *  - LLM indisponible (Flux.error)  : sources → error
 *  - ChromaDB indisponible           : error (pas de sources)
 *  - requête invalide (blank)        : géré en amont par @Valid — non testé ici
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagServiceStreamTest {

    @Mock private ChromaDbClient chromaDbClient;
    @Mock private EmbeddingService embeddingService;
    @Mock private LlmChatClient llmChatClient;
    @Mock private SpectraProperties props;
    @Mock private SpectraProperties.PipelineProperties pipeline;

    private RagService ragService;

    @BeforeEach
    void setUp() {
        when(props.pipeline()).thenReturn(pipeline);
        when(pipeline.generationTimeoutSeconds()).thenReturn(30);
        when(props.chromadb()).thenReturn(null);   // uses default collection name
        when(props.longContextRag()).thenReturn(null);

        ragService = new RagService(
                chromaDbClient, embeddingService, llmChatClient,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                props, new ObjectMapper().findAndRegisterModules(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    // ── #7 — chemin nominal ────────────────────────────────────────────────────

    @Test
    void queryStream_useRagFalse_emitsSourcesTokensDone() {
        when(llmChatClient.chatStream(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn(Flux.just("Bonjour", " monde"));

        Flux<ServerSentEvent<String>> stream = ragService.queryStream(directRequest());

        StepVerifier.create(stream)
                .expectNextMatches(e -> "sources".equals(e.event()) && "[]".equals(e.data()))
                .expectNextMatches(e -> "token".equals(e.event()) && "Bonjour".equals(e.data()))
                .expectNextMatches(e -> "token".equals(e.event()) && " monde".equals(e.data()))
                .expectNextMatches(e -> "done".equals(e.event()))
                .verifyComplete();
    }

    @Test
    void queryStream_useRagFalse_doneEventContainsDirectStrategy() {
        when(llmChatClient.chatStream(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn(Flux.just("ok"));

        Flux<ServerSentEvent<String>> stream = ragService.queryStream(directRequest());

        StepVerifier.create(stream)
                .expectNextMatches(e -> "sources".equals(e.event()))
                .expectNextMatches(e -> "token".equals(e.event()))
                .expectNextMatches(e -> "done".equals(e.event())
                        && e.data() != null && e.data().contains("\"ragStrategy\":\"DIRECT\""))
                .verifyComplete();
    }

    @Test
    void queryStream_useRagFalse_doneEventContainsChunkCount() {
        when(llmChatClient.chatStream(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn(Flux.just("ok"));

        Flux<ServerSentEvent<String>> stream = ragService.queryStream(directRequest());

        StepVerifier.create(stream)
                .expectNextMatches(e -> "sources".equals(e.event()))
                .expectNextMatches(e -> "token".equals(e.event()))
                .expectNextMatches(e -> "done".equals(e.event())
                        && e.data() != null && e.data().contains("\"chunkCount\":0"))
                .verifyComplete();
    }

    /**
     * Un message d'erreur contenant des guillemets, antislashs ou retours à la ligne doit
     * produire un JSON valide (l'ancien échappement manuel cassait le parsing côté client).
     */
    @Test
    void queryStream_errorMessageWithSpecialChars_producesValidJson() {
        when(llmChatClient.chatStream(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenThrow(new RuntimeException("Erreur \"grave\"\navec \\ retour à la ligne"));

        Flux<ServerSentEvent<String>> stream = ragService.queryStream(directRequest());

        StepVerifier.create(stream)
                .expectNextMatches(e -> {
                    if (!"error".equals(e.event()) || e.data() == null) return false;
                    try {
                        var node = new ObjectMapper().readTree(e.data());
                        return "Erreur \"grave\"\navec \\ retour à la ligne".equals(node.get("message").asText());
                    } catch (Exception ex) {
                        return false;
                    }
                })
                .verifyComplete();
    }

    // ── #8 — LLM indisponible ─────────────────────────────────────────────────

    /**
     * Le Flux retourné par chatStream émet une erreur réactive.
     * Sources est déjà émis ; l'erreur arrive ensuite → event:error.
     */
    @Test
    void queryStream_llmFluxError_emitsSourcesThenErrorEvent() {
        when(llmChatClient.chatStream(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn(Flux.error(new RuntimeException("LLM indisponible")));

        Flux<ServerSentEvent<String>> stream = ragService.queryStream(directRequest());

        StepVerifier.create(stream)
                .expectNextMatches(e -> "sources".equals(e.event()))
                .expectNextMatches(e -> "error".equals(e.event())
                        && e.data() != null && e.data().contains("LLM indisponible"))
                .verifyComplete();
    }

    /**
     * chatStream() lève une exception synchrone dans le Callable.
     * Aucun event:sources n'est émis avant l'erreur.
     */
    @Test
    void queryStream_llmSyncException_emitsOnlyErrorEvent() {
        when(llmChatClient.chatStream(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenThrow(new RuntimeException("Connexion refusée"));

        Flux<ServerSentEvent<String>> stream = ragService.queryStream(directRequest());

        StepVerifier.create(stream)
                .expectNextMatches(e -> "error".equals(e.event())
                        && e.data() != null && e.data().contains("Connexion refusée"))
                .verifyComplete();
    }

    // ── #8 — ChromaDB indisponible ────────────────────────────────────────────

    /**
     * getOrCreateCollection() lève ChromaDbUnavailableException (circuit breaker ouvert).
     * Le retrieval échoue dans le pipeline → stage(retrieval) puis event:error, pas de sources.
     */
    @Test
    void queryStream_chromaDbUnavailable_emitsStageThenErrorEvent() {
        when(chromaDbClient.getOrCreateCollection(anyString()))
                .thenThrow(new ChromaDbClient.ChromaDbUnavailableException(
                        "ChromaDB temporairement indisponible", new RuntimeException("timeout")));

        when(embeddingService.embed(anyString()))
                .thenReturn(List.of(0.1f, 0.2f, 0.3f));

        Flux<ServerSentEvent<String>> stream = ragService.queryStream(ragRequest());

        StepVerifier.create(stream)
                .expectNextMatches(e -> "stage".equals(e.event())
                        && e.data() != null && e.data().contains("retrieval"))
                .expectNextMatches(e -> "error".equals(e.event())
                        && e.data() != null && e.data().contains("ChromaDB"))
                .verifyComplete();
    }

    /**
     * L'embedding lève une exception (service embed down).
     * Erreur pendant le retrieval → stage(retrieval) puis event:error.
     */
    @Test
    void queryStream_embeddingUnavailable_emitsStageThenErrorEvent() {
        when(chromaDbClient.getOrCreateCollection(anyString())).thenReturn("col-id-1");
        when(embeddingService.embed(anyString()))
                .thenThrow(new LlamaCppEmbeddingClient.EmbeddingUnavailableException(
                        "Embedding indisponible", new RuntimeException("cb open")));

        Flux<ServerSentEvent<String>> stream = ragService.queryStream(ragRequest());

        StepVerifier.create(stream)
                .expectNextMatches(e -> "stage".equals(e.event()))
                .expectNextMatches(e -> "error".equals(e.event()))
                .verifyComplete();
    }

    /**
     * query() ChromaDB indisponible → retourne une QueryResponse avec un message d'erreur
     * plutôt que de laisser l'exception traverser l'API.
     */
    @Test
    void query_chromaDbUnavailable_propagatesException() {
        when(chromaDbClient.getOrCreateCollection(anyString()))
                .thenThrow(new ChromaDbClient.ChromaDbUnavailableException(
                        "ChromaDB temporairement indisponible", new RuntimeException()));
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> ragService.query(ragRequest()))
                .isInstanceOf(ChromaDbClient.ChromaDbUnavailableException.class);
    }

    // ── Pipeline complet porté au streaming (adaptive / agentic / self-RAG) ──

    /** Retrieval standard : un chunk pertinent, format « listes de listes » de ChromaDB. */
    private void stubStandardRetrieval() {
        when(chromaDbClient.getOrCreateCollection(anyString())).thenReturn("col-id-1");
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f, 0.3f));
        when(chromaDbClient.query(anyString(), anyList(), anyInt())).thenReturn(Map.of(
                "documents", List.of(List.of("Le péage de sortie est conforme.")),
                "metadatas", List.of(List.of(Map.of("sourceFile", "peage.json"))),
                "distances", List.of(List.of(0.12))));
    }

    private RagService serviceWith(Optional<AdaptiveRagService> adaptive,
                                   Optional<AgenticRagService> agentic,
                                   Optional<SelfRagService> selfRag) {
        return new RagService(
                chromaDbClient, embeddingService, llmChatClient,
                Optional.empty(), Optional.empty(), agentic,
                Optional.empty(), Optional.empty(), adaptive,
                selfRag, Optional.empty(), Optional.empty(),
                props, new ObjectMapper().findAndRegisterModules(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /** Chaque étape de la timeline publie un timer Micrometer spectra.rag.stage. */
    @Test
    void queryStream_recordsPerStageTimers() {
        stubStandardRetrieval();
        when(llmChatClient.chatStream(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn(Flux.just("ok"));

        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        RagService svc = new RagService(
                chromaDbClient, embeddingService, llmChatClient,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                props, new ObjectMapper().findAndRegisterModules(), registry);

        StepVerifier.create(svc.queryStream(ragRequest())).thenConsumeWhile(e -> true).verifyComplete();

        var retrieval = registry.find("spectra.rag.stage").tag("stage", "retrieval").timer();
        var generation = registry.find("spectra.rag.stage").tag("stage", "generation").timer();
        org.assertj.core.api.Assertions.assertThat(retrieval).isNotNull();
        org.assertj.core.api.Assertions.assertThat(retrieval.count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(generation).isNotNull();
        org.assertj.core.api.Assertions.assertThat(generation.count()).isEqualTo(1);
    }

    /** Pipeline standard : stage(retrieval) → sources → token* → done. */
    @Test
    void queryStream_standardPipeline_emitsStageSourcesTokensDone() {
        stubStandardRetrieval();
        when(llmChatClient.chatStream(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn(Flux.just("Conforme", "."));

        StepVerifier.create(ragService.queryStream(ragRequest()))
                .expectNextMatches(e -> "stage".equals(e.event()) && e.data().contains("retrieval"))
                .expectNextMatches(e -> "sources".equals(e.event()) && e.data().contains("peage.json"))
                .expectNextMatches(e -> "token".equals(e.event()) && "Conforme".equals(e.data()))
                .expectNextMatches(e -> "token".equals(e.event()) && ".".equals(e.data()))
                .expectNextMatches(e -> "done".equals(e.event())
                        && e.data().contains("\"ragStrategy\":\"STANDARD\"")
                        && e.data().contains("\"chunkCount\":1")
                        // budget d'entrée : "Le péage de sortie est conforme." = 32 caractères
                        && e.data().contains("\"contextChars\":32"))
                .verifyComplete();
    }

    /** Le contexte injecté numérote chaque passage [n] (résolution des citations côté client). */
    @Test
    void queryStream_standardPipeline_numbersContextPassages() {
        stubStandardRetrieval();
        org.mockito.ArgumentCaptor<String> systemPrompt = org.mockito.ArgumentCaptor.forClass(String.class);
        when(llmChatClient.chatStream(systemPrompt.capture(), anyString(), anyFloat(), anyFloat()))
                .thenReturn(Flux.just("ok"));

        StepVerifier.create(ragService.queryStream(ragRequest()))
                .thenConsumeWhile(e -> true)
                .verifyComplete();

        org.assertj.core.api.Assertions.assertThat(systemPrompt.getValue())
                .contains("[1] (Source: peage.json)")
                .contains("cite tes sources");
    }

    /** Adaptive RAG en streaming : classification DIRECT → réponse directe streamée. */
    @Test
    void queryStream_adaptiveDirect_streamsDirectAnswer() {
        AdaptiveRagService adaptive = org.mockito.Mockito.mock(AdaptiveRagService.class);
        when(adaptive.classifyQuery(anyString())).thenReturn(AdaptiveRagService.RagStrategy.DIRECT);
        when(llmChatClient.chatStream(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn(Flux.just("Bonjour !"));

        RagService svc = serviceWith(Optional.of(adaptive), Optional.empty(), Optional.empty());

        StepVerifier.create(svc.queryStream(ragRequest()))
                .expectNextMatches(e -> "stage".equals(e.event()) && e.data().contains("routing"))
                .expectNextMatches(e -> "sources".equals(e.event()) && "[]".equals(e.data()))
                .expectNextMatches(e -> "token".equals(e.event()) && "Bonjour !".equals(e.data()))
                .expectNextMatches(e -> "done".equals(e.event())
                        && e.data().contains("\"ragStrategy\":\"DIRECT\""))
                .verifyComplete();
    }

    /** Adaptive → AGENTIC : la boucle ReAct tourne, réponse émise en bloc, itérations dans done. */
    @Test
    void queryStream_adaptiveAgentic_runsReactLoopAndReportsIterations() {
        AdaptiveRagService adaptive = org.mockito.Mockito.mock(AdaptiveRagService.class);
        when(adaptive.classifyQuery(anyString())).thenReturn(AdaptiveRagService.RagStrategy.AGENTIC);

        AgenticRagService agentic = org.mockito.Mockito.mock(AgenticRagService.class);
        when(agentic.query(any(), anyList(), anyList(), anyList(), anyBoolean(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    AgenticRagService.SearchProgressListener listener = inv.getArgument(6);
                    if (listener != null) listener.onSearch(1, "normes tunnels autoroutiers");
                    return new fr.spectra.dto.QueryResponse(
                            "Réponse agentique.",
                            List.of(new fr.spectra.dto.QueryResponse.Source("chunk", "doc.pdf", 0.1)),
                            42, false, false, true, 2,
                            fr.spectra.dto.QueryResponse.AgenticStopReason.ANSWER);
                });

        stubStandardRetrieval();
        RagService svc = serviceWith(Optional.of(adaptive), Optional.of(agentic), Optional.empty());

        StepVerifier.create(svc.queryStream(ragRequest()))
                .expectNextMatches(e -> "stage".equals(e.event()) && e.data().contains("routing"))
                .expectNextMatches(e -> "stage".equals(e.event()) && e.data().contains("retrieval"))
                .expectNextMatches(e -> "stage".equals(e.event())
                        && e.data().contains("agentic_search")
                        && e.data().contains("normes tunnels autoroutiers"))
                .expectNextMatches(e -> "sources".equals(e.event()) && e.data().contains("doc.pdf"))
                .expectNextMatches(e -> "token".equals(e.event()) && "Réponse agentique.".equals(e.data()))
                .expectNextMatches(e -> "done".equals(e.event())
                        && e.data().contains("\"ragStrategy\":\"AGENTIC\"")
                        && e.data().contains("\"agenticIterations\":2")
                        && e.data().contains("\"agenticStopReason\":\"ANSWER\""))
                .verifyComplete();
    }

    /** Self-RAG en streaming : brouillon streamé → réflexion → replace → version raffinée. */
    @Test
    void queryStream_selfRagRefinement_emitsReplaceThenRefinedTokens() {
        SelfRagService selfRag = org.mockito.Mockito.mock(SelfRagService.class);
        SelfRagService.ReflectionScores badScores = new SelfRagService.ReflectionScores(
                SelfRagService.IsRel.RELEVANT, SelfRagService.IsSup.NO_SUPPORT, SelfRagService.IsUse.NOT_USEFUL);
        SelfRagService.ReflectionScores goodScores = new SelfRagService.ReflectionScores(
                SelfRagService.IsRel.RELEVANT, SelfRagService.IsSup.FULLY_SUPPORTED, SelfRagService.IsUse.USEFUL);
        when(selfRag.evaluate(anyString(), anyList(), anyString()))
                .thenReturn(badScores).thenReturn(goodScores);
        when(selfRag.requiresRefinement(badScores)).thenReturn(true);
        when(selfRag.maxReflectionIterations()).thenReturn(1);
        when(selfRag.refineSystemPrompt(anyString())).thenReturn("prompt renforcé");

        stubStandardRetrieval();
        when(llmChatClient.chatStream(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn(Flux.just("Brouillon."))
                .thenReturn(Flux.just("Réponse raffinée."));

        RagService svc = serviceWith(Optional.empty(), Optional.empty(), Optional.of(selfRag));

        StepVerifier.create(svc.queryStream(ragRequest()))
                .expectNextMatches(e -> "stage".equals(e.event()) && e.data().contains("retrieval"))
                .expectNextMatches(e -> "sources".equals(e.event()))
                .expectNextMatches(e -> "token".equals(e.event()) && "Brouillon.".equals(e.data()))
                .expectNextMatches(e -> "stage".equals(e.event()) && e.data().contains("reflection"))
                .expectNextMatches(e -> "replace".equals(e.event()))
                .expectNextMatches(e -> "stage".equals(e.event()) && e.data().contains("refining"))
                .expectNextMatches(e -> "token".equals(e.event()) && "Réponse raffinée.".equals(e.data()))
                .expectNextMatches(e -> "done".equals(e.event())
                        && e.data().contains("\"selfRagApplied\":true")
                        && e.data().contains("FULLY_SUPPORTED"))
                .verifyComplete();
    }

    /** Self-RAG : brouillon jugé suffisant → pas de replace, done avec les scores. */
    @Test
    void queryStream_selfRagGoodDraft_noReplaceEvent() {
        SelfRagService selfRag = org.mockito.Mockito.mock(SelfRagService.class);
        SelfRagService.ReflectionScores goodScores = new SelfRagService.ReflectionScores(
                SelfRagService.IsRel.RELEVANT, SelfRagService.IsSup.FULLY_SUPPORTED, SelfRagService.IsUse.USEFUL);
        when(selfRag.evaluate(anyString(), anyList(), anyString())).thenReturn(goodScores);
        when(selfRag.requiresRefinement(goodScores)).thenReturn(false);

        stubStandardRetrieval();
        when(llmChatClient.chatStream(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn(Flux.just("Bonne réponse."));

        RagService svc = serviceWith(Optional.empty(), Optional.empty(), Optional.of(selfRag));

        StepVerifier.create(svc.queryStream(ragRequest()))
                .expectNextMatches(e -> "stage".equals(e.event()))
                .expectNextMatches(e -> "sources".equals(e.event()))
                .expectNextMatches(e -> "token".equals(e.event()) && "Bonne réponse.".equals(e.data()))
                .expectNextMatches(e -> "stage".equals(e.event()) && e.data().contains("reflection"))
                .expectNextMatches(e -> "done".equals(e.event())
                        && e.data().contains("\"selfRagApplied\":false")
                        && e.data().contains("RELEVANT/FULLY_SUPPORTED/USEFUL"))
                .verifyComplete();
    }

    /** L'événement done porte la timeline serveur des étapes (durée + compteurs). */
    @Test
    void queryStream_standardPipeline_doneCarriesStageTimeline() {
        stubStandardRetrieval();
        when(llmChatClient.chatStream(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn(Flux.just("ok"));

        StepVerifier.create(ragService.queryStream(ragRequest()))
                .expectNextMatches(e -> "stage".equals(e.event()))
                .expectNextMatches(e -> "sources".equals(e.event()))
                .expectNextMatches(e -> "token".equals(e.event()))
                .expectNextMatches(e -> "done".equals(e.event())
                        && e.data().contains("\"stages\":[")
                        && e.data().contains("\"stage\":\"retrieval\"")
                        && e.data().contains("\"stage\":\"generation\""))
                .verifyComplete();
    }

    /**
     * Surcharge par requête : corrective forcé à false désactive le grading même quand le
     * service est présent (toggle Playground / comparaison A/B).
     */
    @Test
    void queryStream_correctiveOverriddenOff_skipsGradingStage() {
        CorrectiveRagService corrective = org.mockito.Mockito.mock(CorrectiveRagService.class);
        stubStandardRetrieval();
        when(llmChatClient.chatStream(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn(Flux.just("ok"));

        RagService svc = new RagService(
                chromaDbClient, embeddingService, llmChatClient,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(corrective), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                props, new ObjectMapper().findAndRegisterModules(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        RagOverrides ov = new RagOverrides(null, null, null, null, null, false, null, null);
        QueryRequest req = new QueryRequest("Question ?", null, null, null, null, null, null, true, ov);

        StepVerifier.create(svc.queryStream(req))
                .expectNextMatches(e -> "stage".equals(e.event()) && e.data().contains("retrieval"))
                .expectNextMatches(e -> "sources".equals(e.event()))
                .expectNextMatches(e -> "token".equals(e.event()))
                .expectNextMatches(e -> "done".equals(e.event())
                        && e.data().contains("\"correctiveApplied\":false")
                        && !e.data().contains("\"stage\":\"grading\""))
                .verifyComplete();
        org.mockito.Mockito.verifyNoInteractions(corrective);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static QueryRequest directRequest() {
        return new QueryRequest("Qu'est-ce que le RAG?",
                null, null, null, null, null, null, false);
    }

    private static QueryRequest ragRequest() {
        return new QueryRequest("Qu'est-ce que le RAG?",
                null, null, null, null, null, null, true);
    }
}
