package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.ConversationMessage;
import fr.spectra.dto.QueryRequest;
import fr.spectra.dto.RagOverrides;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagResilienceRegressionTest {
    private final ChromaDbClient chroma = mock(ChromaDbClient.class);
    private final EmbeddingService embed = mock(EmbeddingService.class);
    private final LlmChatClient llm = mock(LlmChatClient.class);
    private final FtsService fts = mock(FtsService.class);
    private final SpectraProperties props = mock(SpectraProperties.class);
    private final ActiveModelProfileService profiles = mock(ActiveModelProfileService.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> users = new ArrayList<>();

    @BeforeEach void setUp() {
        when(props.pipeline()).thenReturn(new SpectraProperties.PipelineProperties(512, 64, 32, 60, 30, 4));
        when(profiles.current()).thenReturn(new ActiveModelProfileService.ModelProfile("test", "Assistant fiable.", null, null));
        when(chroma.getOrCreateCollection(anyString())).thenReturn("corpus");
        when(embed.embed(anyString())).thenReturn(List.of(.1f, .2f));
        when(llm.servedContextTokens()).thenReturn(OptionalInt.of(2048));
        when(chroma.query(anyString(), anyList(), anyInt())).thenReturn(Map.of(
                "documents", List.of(List.of("Valeur attestée : 10.")),
                "metadatas", List.of(List.of(Map.of("sourceFile", "preuve.txt"))),
                "distances", List.of(List.of(.2))));
        when(llm.chat(anyString(), anyString(), anyFloat(), anyFloat())).thenAnswer(i -> {
            users.add(i.getArgument(1)); return "Réponse [1].";
        });
        when(llm.chatStream(anyString(), anyString(), anyFloat(), anyFloat())).thenAnswer(i -> {
            users.add(i.getArgument(1)); return Flux.just("Réponse [1].");
        });
        when(fts.search(anyString(), anyString(), anyInt())).thenReturn(List.of(
                new BM25Index.ScoredDoc("lexical", "Valeur lexicale : 10.", "lexical.txt", 3.5f)));
    }

    private QueryRequest request(List<ConversationMessage> history) {
        return new QueryRequest("Quelle valeur ?", 5, 20, "corpus", .7f, .9f, history, true);
    }

    private RagService service(boolean conversation, boolean hybrid) {
        return new RagService(chroma, embed, llm, Optional.empty(),
                hybrid ? Optional.of(new HybridSearchService(chroma, fts, props)) : Optional.empty(),
                Optional.empty(), conversation ? Optional.of(new ConversationalRagService(llm)) : Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                profiles, props, mapper, metrics);
    }

    private List<ServerSentEvent<String>> stream(RagService service, QueryRequest request) {
        return service.queryStream(request).collectList().block(Duration.ofSeconds(5));
    }

    private String sources(List<ServerSentEvent<String>> events) {
        assertThat(events).noneMatch(e -> "error".equals(e.event()));
        assertThat(events).anyMatch(e -> "done".equals(e.event()));
        return events.stream().filter(e -> "sources".equals(e.event())).findFirst().orElseThrow().data();
    }

    private void assertHistorySurvives() {
        var request = request(List.of(new ConversationMessage("user", "Projet ALPHA")));
        var service = service(true, false);
        var response = service.query(request);
        var events = stream(service, request);
        assertThat(response.conversationalApplied()).isFalse();
        assertThat(users).hasSize(2).allSatisfy(user -> assertThat(user).contains("Projet ALPHA"));
        sources(events);
    }

    @Test void unchangedRewriteRetainsHistoryInJsonAndSse() {
        when(llm.chat(anyString(), anyString())).thenReturn("Quelle valeur ?");
        assertHistorySurvives();
    }

    @Test void blankRewriteRetainsHistoryInJsonAndSse() {
        when(llm.chat(anyString(), anyString())).thenReturn("  ");
        assertHistorySurvives();
    }

    @Test void failedRewriteRetainsHistoryInJsonAndSse() {
        when(llm.chat(anyString(), anyString())).thenThrow(new IllegalStateException("LLM indisponible"));
        assertHistorySurvives();
    }

    @Test void unavailableConversationModuleDoesNotInjectHistory() {
        var request = request(List.of(new ConversationMessage("user", "Projet ALPHA")));
        var service = service(false, false);
        service.query(request);
        sources(stream(service, request));
        assertThat(users).allSatisfy(user -> assertThat(user).isEqualTo("Quelle valeur ?"));
    }

    @Test void unchangedRewriteStillBudgetsTheHistoryBeforeGeneration() {
        when(llm.chat(anyString(), anyString())).thenReturn("Quelle valeur ?");
        var request = request(List.of(new ConversationMessage("user", "historique ".repeat(2000))));
        var service = service(true, false);
        assertThatThrownBy(() -> service.query(request)).isInstanceOf(IllegalArgumentException.class);
        assertThat(stream(service, request)).anyMatch(e -> "error".equals(e.event()));
        assertThat(users).isEmpty();
    }

    @Test void conversationOverrideDisablesHistoryEvenWhenTheModuleIsPresent() {
        var request = request(List.of(new ConversationMessage("user", "Projet ALPHA")));
        var service = service(true, false);
        service.query(request, new RagOverrides(null, false, null, null, null, null, null, null));
        assertThat(users).containsExactly("Quelle valeur ?");
        verify(llm, never()).chat(anyString(), anyString());
    }

    @Test void absentMetadataAndDistancesUseUnknownProvenanceInJsonAndSse() {
        when(chroma.query(anyString(), anyList(), anyInt())).thenReturn(Map.of(
                "documents", List.of(List.of("preuve"))));
        var service = service(false, false);
        var response = service.query(request(List.of()));
        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().getFirst().sourceFile()).isEqualTo("inconnu");
        assertThat(response.sources().getFirst().distance()).isEqualTo(1.0);
        assertThat(sources(stream(service, request(List.of())))).contains("inconnu");
    }

    @Test void nullDocumentsAreDroppedWithoutShiftingMetadataOrDistances() {
        Map<String, Object> result = new HashMap<>();
        result.put("documents", List.of(Arrays.asList(null, "preuve un", "preuve deux")));
        result.put("metadatas", List.of(Arrays.asList(Map.of("sourceFile", "à ignorer"),
                Map.of("sourceFile", "un.txt"), null)));
        result.put("distances", List.of(Arrays.asList(.01, .4, null)));
        when(chroma.query(anyString(), anyList(), anyInt())).thenReturn(result);
        var service = service(false, false);
        var response = service.query(request(List.of()));
        assertThat(response.sources()).extracting(s -> s.sourceFile()).containsExactly("un.txt", "inconnu");
        assertThat(response.sources()).extracting(s -> s.distance()).containsExactly(.4, 1.0);
        assertThat(sources(stream(service, request(List.of())))).contains("un.txt", "inconnu").doesNotContain("à ignorer");
    }

    @Test void embeddingOutageUsesRealBm25InJsonAndSseAndReportsDegradation() {
        when(embed.embed(anyString())).thenThrow(new IllegalStateException("embeddings indisponibles"));
        var service = service(false, true);
        var response = service.query(request(List.of()));
        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().getFirst().sourceFile()).isEqualTo("lexical.txt");
        assertThat(response.sources().getFirst().bm25Score()).isEqualTo(3.5f);
        assertThat(sources(stream(service, request(List.of())))).contains("lexical.txt");
        verify(chroma, never()).query(anyString(), anyList(), anyInt());
        assertThat(metrics.get("spectra.rag.retrieval.degraded").tag("mode", "bm25_only")
                .tag("reason", "embedding_unavailable").counter().count()).isEqualTo(2);
    }

    @Test void emptyEmbeddingAlsoUsesLexicalFallback() {
        when(embed.embed(anyString())).thenReturn(List.of());
        assertThat(service(false, true).query(request(List.of())).sources())
                .extracting(s -> s.sourceFile()).containsExactly("lexical.txt");
        verify(chroma, never()).query(anyString(), anyList(), anyInt());
    }

    @Test void disablingHybridDoesNotSilentlyActivateBm25() {
        when(embed.embed(anyString())).thenThrow(new IllegalStateException("embeddings indisponibles"));
        var service = service(false, true);
        assertThatThrownBy(() -> service.query(request(List.of()),
                new RagOverrides(null, null, null, false, null, null, null, null)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(fts);
    }

    @Test void modelMismatchIsNotHiddenByLexicalFallback() {
        when(chroma.getOrCreateCollection(anyString())).thenThrow(
                new ChromaDbClient.EmbeddingModelMismatchException("réindexation requise"));
        var service = service(false, true);
        assertThatThrownBy(() -> service.query(request(List.of())))
                .isInstanceOf(ChromaDbClient.EmbeddingModelMismatchException.class);
        assertThat(stream(service, request(List.of()))).anyMatch(e -> "error".equals(e.event()));
        verifyNoInteractions(fts, embed);
    }

    @Test void modelMismatchRaisedDuringEmbeddingAlsoPropagates() {
        when(embed.embed(anyString())).thenThrow(
                new ChromaDbClient.EmbeddingModelMismatchException("modèle incompatible"));
        assertThatThrownBy(() -> service(false, true).query(request(List.of())))
                .isInstanceOf(ChromaDbClient.EmbeddingModelMismatchException.class);
        verifyNoInteractions(fts);
        assertThat(metrics.find("spectra.rag.retrieval.degraded").counter()).isNull();
    }
}
