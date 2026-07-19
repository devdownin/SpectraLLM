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
                props, new ObjectMapper().findAndRegisterModules());
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
     * Le retrieval échoue dans le Mono.fromCallable → event:error, pas de sources.
     */
    @Test
    void queryStream_chromaDbUnavailable_emitsOnlyErrorEvent() {
        when(chromaDbClient.getOrCreateCollection(anyString()))
                .thenThrow(new ChromaDbClient.ChromaDbUnavailableException(
                        "ChromaDB temporairement indisponible", new RuntimeException("timeout")));

        when(embeddingService.embed(anyString()))
                .thenReturn(List.of(0.1f, 0.2f, 0.3f));

        Flux<ServerSentEvent<String>> stream = ragService.queryStream(ragRequest());

        StepVerifier.create(stream)
                .expectNextMatches(e -> "error".equals(e.event())
                        && e.data() != null && e.data().contains("ChromaDB"))
                .verifyComplete();
    }

    /**
     * L'embedding lève une exception (service embed down).
     * Erreur dans le callable → event:error seul.
     */
    @Test
    void queryStream_embeddingUnavailable_emitsOnlyErrorEvent() {
        when(chromaDbClient.getOrCreateCollection(anyString())).thenReturn("col-id-1");
        when(embeddingService.embed(anyString()))
                .thenThrow(new LlamaCppEmbeddingClient.EmbeddingUnavailableException(
                        "Embedding indisponible", new RuntimeException("cb open")));

        Flux<ServerSentEvent<String>> stream = ragService.queryStream(ragRequest());

        StepVerifier.create(stream)
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
