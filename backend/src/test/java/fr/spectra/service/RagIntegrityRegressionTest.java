package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.QueryRequest;
import fr.spectra.dto.QueryResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagIntegrityRegressionTest {
    private final ChromaDbClient chroma = mock(ChromaDbClient.class);
    private final EmbeddingService embed = mock(EmbeddingService.class);
    private final LlmChatClient llm = mock(LlmChatClient.class);
    private final SpectraProperties props = mock(SpectraProperties.class);
    private final ActiveModelProfileService profiles = mock(ActiveModelProfileService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> generationPrompts = new ArrayList<>();
    private final List<String> generationUsers = new ArrayList<>();

    @BeforeEach void setUp() {
        when(props.pipeline()).thenReturn(new SpectraProperties.PipelineProperties(512, 64, 32, 60, 30, 4));
        when(profiles.current()).thenReturn(new ActiveModelProfileService.ModelProfile("test", "Assistant fiable.", null, null));
        when(chroma.getOrCreateCollection(anyString())).thenReturn("corpus");
        when(embed.embed(anyString())).thenReturn(List.of(.1f, .2f));
        when(llm.servedContextTokens()).thenReturn(OptionalInt.of(2048));
        when(llm.chat(anyString(), anyString(), anyFloat(), anyFloat())).thenAnswer(i -> {
            generationPrompts.add(i.getArgument(0)); generationUsers.add(i.getArgument(1)); return "Réponse [1].";
        });
        when(llm.chatStream(anyString(), anyString(), anyFloat(), anyFloat())).thenAnswer(i -> {
            generationPrompts.add(i.getArgument(0)); generationUsers.add(i.getArgument(1)); return Flux.just("Réponse [1].");
        });
    }

    private QueryRequest request(int max) {
        return new QueryRequest("Quelle valeur ?", max, 20, "corpus", .7f, .9f, List.of(), true);
    }

    private void retrieval(List<String> texts) {
        when(chroma.query(anyString(), anyList(), anyInt())).thenReturn(Map.of(
                "documents", List.of(texts),
                "metadatas", List.of(IntStream.range(0, texts.size()).mapToObj(i -> Map.of("sourceFile", "doc-" + i)).toList()),
                "distances", List.of(texts.stream().map(t -> .1).toList())));
    }

    private RagService service(boolean compression, boolean agentic) {
        return service(compression, agentic, false, false);
    }

    private RagService service(boolean compression, boolean agentic, boolean conversation, boolean selfRag) {
        AdaptiveRagService router = mock(AdaptiveRagService.class);
        when(router.classifyQuery(anyString())).thenReturn(AdaptiveRagService.RagStrategy.AGENTIC);
        var agent = new AgenticRagService(chroma, embed, llm, Optional.empty(), Optional.empty(), props, new SimpleMeterRegistry());
        return new RagService(chroma, embed, llm, Optional.empty(), Optional.empty(),
                agentic ? Optional.of(agent) : Optional.empty(),
                conversation ? Optional.of(new ConversationalRagService(llm)) : Optional.empty(), Optional.empty(),
                agentic ? Optional.of(router) : Optional.empty(),
                selfRag ? Optional.of(new SelfRagService(llm, props)) : Optional.empty(),
                compression ? Optional.of(new ContextCompressionService(llm)) : Optional.empty(),
                Optional.empty(), profiles, props, mapper, new SimpleMeterRegistry());
    }

    private List<ServerSentEvent<String>> stream(RagService service, QueryRequest request) {
        return service.queryStream(request).collectList().block(Duration.ofSeconds(5));
    }

    private int sourceCount(List<ServerSentEvent<String>> events) throws Exception {
        var source = events.stream().filter(e -> "sources".equals(e.event())).findFirst().orElseThrow();
        return mapper.readTree(source.data()).size();
    }

    private void assertBudgetRespected() {
        assertThat(generationPrompts).isNotEmpty();
        for (int i = 0; i < generationPrompts.size(); i++) {
            int estimatedInput = TokenEstimator.estimateTokens(generationPrompts.get(i) + "\n" + generationUsers.get(i));
            assertThat(estimatedInput + 500 + 32).isLessThanOrEqualTo((int) (2048 * .85));
        }
    }

    @Test void standardJsonAndSseFitTheCompletePromptAndPublishOnlySelectedSources() throws Exception {
        retrieval(IntStream.range(0, 20).mapToObj(i -> "Passage " + i + ": " + "texte attesté ".repeat(100)).toList());
        var service = service(false, false);
        var response = service.query(request(20));
        var events = stream(service, request(20));
        assertBudgetRespected();
        assertThat(response.sources().size()).isBetween(1, 19);
        assertThat(sourceCount(events)).isEqualTo(response.sources().size());
        assertThat(events).noneMatch(e -> "error".equals(e.event()));
        for (int i = 0; i < response.sources().size(); i++) {
            assertThat(generationPrompts.get(0)).contains("[" + (i + 1) + "] (Source: " + response.sources().get(i).sourceFile() + ")");
        }
    }

    @Test void oversizedInstructionsAndQuestionFailBeforeGeneration() {
        retrieval(List.of("preuve"));
        var request = new QueryRequest("question ".repeat(2000), 1, 20, "corpus", .7f, .9f, List.of(), true);
        assertThatThrownBy(() -> service(false, false).query(request)).isInstanceOf(IllegalArgumentException.class);
        assertThat(generationPrompts).isEmpty();
    }

    @Test void allIrrelevantRemainsEmptyInJsonAndSse() throws Exception {
        retrieval(List.of("La valeur attestée est 10."));
        when(llm.chat(anyString(), anyString())).thenReturn("IRRELEVANT");
        var service = service(true, false);
        var response = service.query(request(1));
        var events = stream(service, request(1));
        assertThat(response.sources()).isEmpty();
        assertThat(response.answer()).contains("Aucun document pertinent");
        assertThat(sourceCount(events)).isZero();
        assertThat(events).anyMatch(e -> "done".equals(e.event()));
        assertThat(generationPrompts).isEmpty();
    }

    @Test void fabricatedCompressionCannotEnterJsonOrSseGeneration() throws Exception {
        retrieval(List.of("La valeur attestée est 10."));
        when(llm.chat(anyString(), anyString())).thenReturn("La valeur est 999.");
        var service = service(true, false);
        var response = service.query(request(1));
        var events = stream(service, request(1));
        assertThat(generationPrompts).allSatisfy(prompt -> {
            assertThat(prompt).contains("La valeur attestée est 10.").doesNotContain("999");
        });
        assertThat(response.sources().getFirst().text()).isEqualTo("La valeur attestée est 10.");
        assertThat(events.stream().filter(e -> "sources".equals(e.event())).findFirst().orElseThrow().data()).doesNotContain("999");
    }

    @Test void agenticSearchCitationsAreResolvableInJsonAndSse() throws Exception {
        retrieval(List.of("preuve initiale"));
        when(llm.chat(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn("ACTION: SEARCH\nQUERY: recherche", "ACTION: ANSWER\nRESPONSE: preuve [2].",
                        "ACTION: SEARCH\nQUERY: recherche", "ACTION: ANSWER\nRESPONSE: preuve [2].");
        when(chroma.query(anyString(), anyList(), eq(2))).thenReturn(Map.of(
                "documents", List.of(List.of("preuve supplémentaire")),
                "metadatas", List.of(List.of(Map.of("sourceFile", "nouveau.txt"))),
                "distances", List.of(List.of(.2))));
        var service = service(false, true);
        QueryResponse response = service.query(request(1));
        var events = stream(service, request(1));
        assertThat(response.answer()).contains("[2]");
        assertThat(response.sources()).hasSize(2);
        assertThat(response.sources().get(1).sourceFile()).isEqualTo("nouveau.txt");
        assertThat(sourceCount(events)).isEqualTo(2);
        assertThat(events).noneMatch(e -> "error".equals(e.event()));
    }

    @Test void agenticSourcesFollowTheBudgetedSelectionRatherThanTheUntrimmedPrefix() {
        when(llm.chat(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn("ACTION: ANSWER\nRESPONSE: preuve [1].");
        var agent = new AgenticRagService(chroma, embed, llm, Optional.empty(), Optional.empty(), props, new SimpleMeterRegistry());
        var response = agent.query(request(1), List.of("texte ".repeat(5000), "preuve courte"),
                List.of(Map.of("sourceFile", "trop-grand.txt"), Map.of("sourceFile", "court.txt")),
                List.of(.1, .2), false, false);
        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().getFirst().sourceFile()).isEqualTo("court.txt");
        assertThat(response.answer()).contains("[1]");
    }

    @Test void agenticFallbackIncludesAndCitesTheAdditionalEvidence() {
        when(props.agenticRag()).thenReturn(new SpectraProperties.AgenticRagProperties(true, 1, 5, "fr", 3000));
        retrieval(List.of("preuve initiale"));
        when(chroma.query(anyString(), anyList(), eq(2))).thenReturn(Map.of(
                "documents", List.of(List.of("preuve supplémentaire")),
                "metadatas", List.of(List.of(Map.of("sourceFile", "nouveau.txt"))),
                "distances", List.of(List.of(.2))));
        when(llm.chat(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn("ACTION: SEARCH\nQUERY: recherche", "preuve finale [2].");
        var response = service(false, true).query(request(1));
        assertThat(response.agenticStopReason()).isEqualTo(QueryResponse.AgenticStopReason.MAX_ITERATIONS);
        assertThat(response.answer()).contains("[2]");
        assertThat(response.sources()).hasSize(2);
        assertThat(response.sources().get(1).sourceFile()).isEqualTo("nouveau.txt");
    }


    @Test void conversationalHistoryIsCountedInTheJsonAndSseBudgets() throws Exception {
        retrieval(IntStream.range(0, 20).mapToObj(i -> "Passage " + i + ": " + "preuve attestée ".repeat(80)).toList());
        when(llm.chat(anyString(), anyString())).thenReturn("Quelle valeur dans le projet ALPHA ?");
        var request = new QueryRequest("Quelle valeur ?", 20, 20, "corpus", .7f, .9f,
                List.of(new fr.spectra.dto.ConversationMessage("user", "Projet ALPHA : " + "historique ".repeat(100))), true);
        var service = service(false, false, true, false);
        var response = service.query(request);
        var events = stream(service, request);
        assertBudgetRespected();
        assertThat(generationUsers).allSatisfy(user -> assertThat(user).contains("Projet ALPHA"));
        assertThat(sourceCount(events)).isEqualTo(response.sources().size());
    }

    @Test void selfRagRefinementAlsoFitsTheJsonAndSseBudgets() {
        retrieval(IntStream.range(0, 20).mapToObj(i -> "Passage " + i + ": " + "preuve attestée ".repeat(60)).toList());
        when(llm.chat(anyString(), anyString())).thenReturn("ISREL: RELEVANT\nISSUP: NO_SUPPORT\nISUSE: NOT_USEFUL");
        var service = service(false, false, false, true);
        var response = service.query(request(20));
        var events = stream(service, request(20));
        assertBudgetRespected();
        assertThat(response.selfRagApplied()).isTrue();
        assertThat(events).anyMatch(e -> "replace".equals(e.event()));
        assertThat(generationPrompts).hasSize(4);
        assertThat(generationPrompts.get(1)).contains("IMPORTANT");
        assertThat(generationPrompts.get(3)).contains("IMPORTANT");
    }

}
