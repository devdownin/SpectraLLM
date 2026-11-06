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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    // ── Outils LOOKUP / OPEN ──────────────────────────────────────────────────

    private record ToolsFixture(AgenticRagService service, ChromaDbClient chroma) {}

    private ToolsFixture toolsService(LlmChatClient llm, boolean tools) {
        ChromaDbClient chroma = mock(ChromaDbClient.class);
        when(chroma.getOrCreateCollection(anyString())).thenReturn("col-1");
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.agenticRag()).thenReturn(new SpectraProperties.AgenticRagProperties(
                true, 3, 5, "fr", 3000, tools));
        return new ToolsFixture(new AgenticRagService(chroma, mock(EmbeddingService.class), llm,
                Optional.empty(), Optional.empty(), props, new SimpleMeterRegistry()), chroma);
    }

    @Test
    void toolsDisabled_promptIsUnchangedAndLookupIsNotAnAction() {
        LlmChatClient llm = mock(LlmChatClient.class);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        when(llm.chat(system.capture(), anyString(), anyFloat(), anyFloat()))
                .thenReturn("THOUGHT: x\nACTION: LOOKUP\nQUERY: ENG-1");
        ToolsFixture f = toolsService(llm, false);

        QueryResponse resp = f.service().query(agenticRequest(), List.of("Texte."),
                List.of(Map.of("sourceFile", "a.md")), List.of(0.1), false, false);

        assertThat(system.getAllValues().getFirst()).doesNotContain("LOOKUP").doesNotContain("OPEN");
        // Format inattendu pour ce prompt : traité comme avant, réponse directe.
        assertThat(resp.agenticStopReason()).isEqualTo(QueryResponse.AgenticStopReason.FORMAT_ERROR);
        verify(f.chroma(), never()).findChunksContaining(anyString(), anyString(), any(), anyInt());
    }

    @Test
    void toolsEnabled_promptDescribesBothTools() {
        LlmChatClient llm = mock(LlmChatClient.class);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        when(llm.chat(system.capture(), anyString(), anyFloat(), anyFloat()))
                .thenReturn("ACTION: ANSWER\nRESPONSE: ok [1].");
        ToolsFixture f = toolsService(llm, true);

        f.service().query(agenticRequest(), List.of("Texte."),
                List.of(Map.of("sourceFile", "a.md")), List.of(0.1), false, false);

        assertThat(system.getValue()).contains("ACTION: LOOKUP", "ACTION: OPEN", "SOURCE: <numéro [n] du passage>",
                "dans l'un de ces formats :").doesNotContain("deux formats");
    }

    @Test
    void lookup_addsTheChunksThatContainTheExactIdentifierThenAnswers() {
        LlmChatClient llm = mock(LlmChatClient.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.chat(anyString(), user.capture(), anyFloat(), anyFloat())).thenReturn(
                "THOUGHT: il me faut le ticket\nACTION: LOOKUP\nQUERY: `ENG-39100`",
                "ACTION: ANSWER\nRESPONSE: Le ticket ENG-39100 est fermé [2].");
        ToolsFixture f = toolsService(llm, true);
        when(f.chroma().findChunksContaining(eq("col-1"), eq("ENG-39100"), any(), anyInt()))
                .thenReturn(List.of(new ChromaDbClient.ChunkHit("ENG-39100 est fermé.",
                        Map.of("sourceFile", "linear/ENG-39100.json", "sha256", "s2"))));

        QueryResponse resp = f.service().query(agenticRequest(), List.of("Texte sans rapport."),
                List.of(Map.of("sourceFile", "a.md", "sha256", "s1")), List.of(0.3), false, false);

        assertThat(resp.answer()).contains("ENG-39100 est fermé [2]");
        assertThat(resp.agenticIterations()).isEqualTo(1);
        // Le second tour voit le chunk trouvé par identifiant, numéroté [2].
        assertThat(user.getAllValues().get(1)).contains("[2] (Source: linear/ENG-39100.json)");
    }

    @Test
    void lookup_withoutAnyHit_stopsTheLoop() {
        LlmChatClient llm = mock(LlmChatClient.class);
        when(llm.chat(anyString(), anyString(), anyFloat(), anyFloat())).thenReturn(
                "ACTION: LOOKUP\nQUERY: INCONNU-1", "Réponse de repli.");
        ToolsFixture f = toolsService(llm, true);
        when(f.chroma().findChunksContaining(anyString(), anyString(), any(), anyInt())).thenReturn(List.of());

        QueryResponse resp = f.service().query(agenticRequest(), List.of("Texte."),
                List.of(Map.of("sourceFile", "a.md")), List.of(0.3), false, false);

        assertThat(resp.agenticStopReason()).isEqualTo(QueryResponse.AgenticStopReason.NO_NEW_CHUNKS);
    }

    @Test
    void open_replacesThePassagesOfTheDocumentByTheWholeDocument() {
        LlmChatClient llm = mock(LlmChatClient.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.chat(anyString(), user.capture(), anyFloat(), anyFloat())).thenReturn(
                "THOUGHT: je veux le document entier\nACTION: OPEN\nSOURCE: [1]",
                "ACTION: ANSWER\nRESPONSE: Dix minutes puis rollback [1].");
        ToolsFixture f = toolsService(llm, true);
        java.util.TreeMap<Integer, String> chunks = new java.util.TreeMap<>();
        chunks.put(0, "Début du runbook. Le seuil est de dix minutes.");
        chunks.put(1, "Suite du runbook. Puis rollback du déploiement.");
        when(f.chroma().getDocumentChunks("col-1", "sha256", "s1", null, 24)).thenReturn(chunks);

        QueryResponse resp = f.service().query(agenticRequest(),
                List.of("Le seuil est de dix minutes.", "Autre document."),
                List.of(Map.of("sourceFile", "runbook.md", "sha256", "s1", "chunkIndex", "0"),
                        Map.of("sourceFile", "autre.md", "sha256", "s2")),
                List.of(0.1, 0.2), false, false);

        // Second tour : le runbook est lu en entier, l'autre passage reste, la numérotation est refaite.
        String second = user.getAllValues().get(1);
        assertThat(second).contains("Début du runbook.").contains("Puis rollback du déploiement.");
        assertThat(second).contains("[2] (Source: autre.md)");
        assertThat(resp.agenticIterations()).isEqualTo(1);
        assertThat(resp.answer()).contains("rollback");
    }

    @Test
    void open_aPassageNumberOutsideTheContext_stopsWithoutReadingAnything() {
        LlmChatClient llm = mock(LlmChatClient.class);
        when(llm.chat(anyString(), anyString(), anyFloat(), anyFloat())).thenReturn(
                "ACTION: OPEN\nSOURCE: 9", "Réponse de repli.");
        ToolsFixture f = toolsService(llm, true);

        QueryResponse resp = f.service().query(agenticRequest(), List.of("Texte."),
                List.of(Map.of("sourceFile", "a.md", "sha256", "s1")), List.of(0.3), false, false);

        assertThat(resp.agenticStopReason()).isEqualTo(QueryResponse.AgenticStopReason.NO_NEW_CHUNKS);
        verify(f.chroma(), never()).getDocumentChunks(anyString(), anyString(), anyString(), any(), anyInt());
    }

    @Test
    void open_aDocumentThatAddsNothing_isNotReopened() {
        LlmChatClient llm = mock(LlmChatClient.class);
        when(llm.chat(anyString(), anyString(), anyFloat(), anyFloat())).thenReturn(
                "ACTION: OPEN\nSOURCE: 1", "Réponse de repli.");
        ToolsFixture f = toolsService(llm, true);
        java.util.TreeMap<Integer, String> same = new java.util.TreeMap<>();
        same.put(0, "Texte.");
        when(f.chroma().getDocumentChunks(anyString(), anyString(), anyString(), any(), anyInt())).thenReturn(same);

        QueryResponse resp = f.service().query(agenticRequest(), List.of("Texte."),
                List.of(Map.of("sourceFile", "a.md", "sha256", "s1")), List.of(0.3), false, false);

        assertThat(resp.agenticStopReason()).isEqualTo(QueryResponse.AgenticStopReason.NO_NEW_CHUNKS);
    }
}
