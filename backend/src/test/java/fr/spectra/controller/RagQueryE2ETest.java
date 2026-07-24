package fr.spectra.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import fr.spectra.service.ChromaDbClient;
import fr.spectra.service.EmbeddingService;
import fr.spectra.service.FeedbackService;
import fr.spectra.service.LlmChatClient;
import fr.spectra.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test de bout en bout de l'interrogation RAG via la couche HTTP.
 *
 * <p>Chaîne RÉELLE : {@code POST /api/query} → {@link QueryController} → {@link RagService}
 * (orchestration réelle : embedding de la question → recherche vectorielle → assemblage du
 * contexte → génération). Seules les frontières d'E/S externes (embedding, ChromaDB, LLM)
 * sont simulées ; tous les modules RAG optionnels sont désactivés ({@code Optional.empty()}).</p>
 */
class RagQueryE2ETest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private EmbeddingService embeddingService;
    private ChromaDbClient chromaDbClient;
    private LlmChatClient llmClient;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f, 0.3f));

        chromaDbClient = mock(ChromaDbClient.class);
        when(chromaDbClient.getOrCreateCollection(anyString())).thenReturn("test-collection");
        // Réponse ChromaDB : un chunk pertinent (format « listes de listes » du client).
        Map<String, Object> chromaResult = Map.of(
                "documents", List.of(List.of("Le passage au peage de sortie est conforme, montant retenu nul.")),
                "metadatas", List.of(List.of(Map.of("sourceFile", "passage.json"))),
                "distances", List.of(List.of(0.12)),
                "ids",       List.of(List.of("chunk-1")));
        when(chromaDbClient.query(anyString(), anyList(), anyInt())).thenReturn(chromaResult);

        llmClient = mock(LlmChatClient.class);
        when(llmClient.chat(anyString(), anyString())).thenReturn("Le passage est conforme.");
        when(llmClient.chat(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn("Le passage est conforme.");

        SpectraProperties props = mock(SpectraProperties.class); // toutes les options → null → défauts

        RagService ragService = new RagService(
                chromaDbClient, embeddingService, llmClient,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                props, objectMapper, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        QueryController controller = new QueryController(ragService, mock(FeedbackService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void ragQuery_retrievesContextAndGeneratesAnswer() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "question", "Le passage au peage est-il conforme ?",
                "useRag", true));

        mockMvc.perform(post("/api/query").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Le passage est conforme."))
                .andExpect(jsonPath("$.sources").isArray())
                .andExpect(jsonPath("$.ragStrategy").value("STANDARD"));

        // Le pipeline a réellement embarqué la question, interrogé le vecteur puis généré.
        verify(embeddingService, atLeastOnce()).embed(anyString());
        verify(chromaDbClient, atLeastOnce()).query(anyString(), anyList(), anyInt());
        verify(llmClient, atLeastOnce()).chat(anyString(), anyString(), anyFloat(), anyFloat());
    }

    @Test
    void directQuery_bypassesRetrieval_callsLlmOnly() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "question", "Bonjour",
                "useRag", false));

        mockMvc.perform(post("/api/query").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Le passage est conforme."))
                .andExpect(jsonPath("$.ragStrategy").value("DIRECT"));

        // Mode direct : aucune recherche vectorielle.
        verify(chromaDbClient, org.mockito.Mockito.never()).query(anyString(), anyList(), anyInt());
    }
}
