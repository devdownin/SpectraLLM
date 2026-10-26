package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.QueryRequest;
import fr.spectra.dto.QueryResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests du chemin Agentic — vérifie que la boucle ReAct numérote le contexte [n] et demande
 * les citations, pour que les marqueurs [n] de la réponse soient résolubles côté Playground
 * (parité avec le chemin STANDARD de {@link RagService}).
 */
class AgenticRagServiceTest {

    private AgenticRagService serviceWith(LlmChatClient llmClient) {
        ChromaDbClient chroma = mock(ChromaDbClient.class);
        when(chroma.getOrCreateCollection(anyString())).thenReturn("col-1");
        SpectraProperties props = mock(SpectraProperties.class); // agenticRag()/chromadb() → null → défauts
        return new AgenticRagService(
                chroma, mock(EmbeddingService.class), llmClient,
                Optional.empty(), Optional.empty(), props, new SimpleMeterRegistry());
    }

    private static QueryRequest agenticRequest() {
        return new QueryRequest("Quelle est la valeur par défaut ?",
                5, null, null, 0.7f, 0.9f, null, true);
    }

    @Test
    void query_numbersContextAndAsksForCitations() {
        LlmChatClient llm = mock(LlmChatClient.class);
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userMsg = ArgumentCaptor.forClass(String.class);
        when(llm.chat(systemPrompt.capture(), userMsg.capture(), anyFloat(), anyFloat()))
                .thenReturn("THOUGHT: j'ai assez d'information\nACTION: ANSWER\nRESPONSE: La valeur par défaut est 512 [1].");

        AgenticRagService service = serviceWith(llm);
        QueryResponse resp = service.query(
                agenticRequest(),
                List.of("La valeur par défaut du chunk est 512 tokens."),
                List.of(Map.of("sourceFile", "config.md")),
                List.of(0.12),
                false, false, null);

        // Le contexte envoyé au LLM numérote le passage et le prompt demande de citer.
        assertThat(userMsg.getValue()).contains("[1] (Source: config.md)");
        assertThat(systemPrompt.getValue()).contains("[n]");
        // La réponse conserve le marqueur, et la source [1] existe pour le résoudre.
        assertThat(resp.answer()).contains("[1]");
        assertThat(resp.sources()).hasSize(1);
        assertThat(resp.sources().get(0).sourceFile()).isEqualTo("config.md");
    }
    private CitationVerifier verifier(boolean strict, RerankerClient ranker) {
        when(ranker.isAvailable()).thenReturn(true);
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.rag()).thenReturn(new SpectraProperties.RagProperties(
                null, null, null, null, true, 0.2f, strict));
        return new CitationVerifier(Optional.of(ranker), props, new SimpleMeterRegistry());
    }

    @Test
    void invalidCitationIsReportedAndRemovedBeforeBufferedAnswerIsReturned() {
        LlmChatClient llm = mock(LlmChatClient.class);
        when(llm.chat(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn("ACTION: ANSWER\nRESPONSE: La valeur est 512 [999].");
        var service = serviceWith(llm);
        var ranker = mock(RerankerClient.class);
        service.setCitationVerifier(verifier(true, ranker));
        QueryResponse response = service.query(agenticRequest(), List.of("La valeur est 512."),
                List.of(Map.of("sourceFile", "config.md")), List.of(0.1), false, false);
        assertThat(response.citations()).isNotNull();
        assertThat(response.citations().invalidCitations()).isEqualTo(1);
        assertThat(response.answer()).doesNotContain("[999]").contains("ne permettent pas");
        assertThat(response.sources()).hasSize(1);
    }

    @Test
    void verifierUsesCompleteBudgetedPassagesRatherThanSourcePreviewsOrDiscardedChunks() {
        LlmChatClient llm = mock(LlmChatClient.class);
        when(llm.chat(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn("ACTION: ANSWER\nRESPONSE: La valeur est 512 [1].");
        String full = "Contexte. ".repeat(30) + "La valeur est 512.";
        var service = serviceWith(llm);
        var ranker = mock(RerankerClient.class);
        when(ranker.rerank(anyString(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new RerankerClient.RankedResult(0, 0.9f)));
        service.setCitationVerifier(verifier(false, ranker));
        QueryResponse response = service.query(agenticRequest(), List.of("X".repeat(25000), full),
                List.of(Map.of("sourceFile", "discarded.md"), Map.of("sourceFile", "served.md")),
                List.of(0.1, 0.2), false, false);
        org.mockito.Mockito.verify(ranker).rerank(anyString(), org.mockito.ArgumentMatchers.eq(List.of(full)),
                org.mockito.ArgumentMatchers.eq(1));
        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().getFirst().text().length()).isEqualTo(203);
        assertThat(response.citations().supportedSentences()).isEqualTo(1);
        assertThat(response.contextChars()).isEqualTo(full.length());
    }
}
