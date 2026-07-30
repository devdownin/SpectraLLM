package fr.spectra.controller;

import fr.spectra.dto.ServiceStatus;
import fr.spectra.service.ChromaDbClient;
import fr.spectra.service.EmbeddingClient;
import fr.spectra.service.FtsService;
import fr.spectra.service.LlmChatClient;
import fr.spectra.service.RerankerClient;
import fr.spectra.service.extraction.LayoutParserClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Vérifie que {@code /api/status} et {@code /api/health/services} rapportent l'état d'une
 * implémentation <strong>quelconque</strong> de {@link RerankerClient}, et non seulement celle
 * du service HTTP Python.
 *
 * <p>Les deux contrôleurs injectaient {@code Optional<CrossEncoderRerankerClient>} — la classe
 * concrète. Un moteur de reranking exécuté dans la JVM (cf.
 * {@code docs/process/audit-python-java.fr.md}) aurait donc silencieusement disparu des deux
 * endpoints : le bean existe, l'{@code Optional} est vide, aucun test n'échoue, et l'UI affiche
 * « reranker absent » alors qu'il tourne. Ce test fige le découplage.
 */
class RerankerHealthReportingTest {

    /** Reranker fictif exécuté dans la JVM : pas d'URL de service, un identifiant d'artefact. */
    private static final class InProcessReranker implements RerankerClient {
        @Override
        public ServiceStatus checkHealth() {
            return new ServiceStatus("reranker", "in-process:mmarco-mMiniLMv2", true, "ok", 3,
                    Map.of("engine", "onnx"));
        }

        @Override
        public List<RankedResult> rerank(String query, List<String> documents, int topN) {
            return List.of();
        }
    }

    private final RerankerClient reranker = new InProcessReranker();
    private LlmChatClient chatClient;
    private EmbeddingClient embeddingClient;
    private ChromaDbClient chromaDbClient;

    @BeforeEach
    void setUp() {
        chatClient = mock(LlmChatClient.class);
        embeddingClient = mock(EmbeddingClient.class);
        chromaDbClient = mock(ChromaDbClient.class);
        when(chatClient.checkHealth()).thenReturn(ok("llm-chat"));
        when(embeddingClient.checkHealth()).thenReturn(ok("llm-embed"));
        when(chromaDbClient.checkHealth()).thenReturn(ok("chromadb"));
    }

    private static ServiceStatus ok(String name) {
        return new ServiceStatus(name, "http://" + name, true, "ok", 1, Map.of());
    }

    @Test
    void statusRapporteUnRerankerNonHttp() throws Exception {
        StatusController controller = new StatusController(
                chatClient, embeddingClient, chromaDbClient,
                Optional.<FtsService>empty(),
                Optional.<LayoutParserClient>empty(),
                Optional.of(reranker));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[?(@.name=='reranker')]").exists())
                .andExpect(jsonPath("$.services[?(@.name=='reranker')].url")
                        .value("in-process:mmarco-mMiniLMv2"))
                .andExpect(jsonPath("$.services[?(@.name=='reranker')].available").value(true));
    }

    @Test
    void healthRapporteUnRerankerNonHttp() throws Exception {
        HealthController controller = new HealthController(
                chatClient, embeddingClient, chromaDbClient,
                Optional.<LayoutParserClient>empty(),
                Optional.of(reranker));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/health/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='reranker')].url")
                        .value("in-process:mmarco-mMiniLMv2"));
    }

    /** Sans reranker configuré, aucune entrée « reranker » n'est publiée. */
    @Test
    void statusSansRerankerNePublieRien() throws Exception {
        StatusController controller = new StatusController(
                chatClient, embeddingClient, chromaDbClient,
                Optional.<FtsService>empty(),
                Optional.<LayoutParserClient>empty(),
                Optional.<RerankerClient>empty());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[?(@.name=='reranker')]").doesNotExist());
    }
}
