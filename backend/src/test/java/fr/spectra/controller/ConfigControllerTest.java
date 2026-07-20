package fr.spectra.controller;

import fr.spectra.service.EmbeddingConsistencyChecker;
import fr.spectra.service.EmbeddingReindexService;
import fr.spectra.service.LlmChatClient;
import fr.spectra.service.RagService;
import fr.spectra.service.ResourceAdvisorService;
import fr.spectra.service.RuntimeParamsMaterializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Test de l'endpoint GET /api/config/rag (disponibilité serveur des modules). */
class ConfigControllerTest {

    private MockMvc mockMvc;
    private RagService ragService;

    @BeforeEach
    void setUp() {
        ragService = mock(RagService.class);
        ConfigController controller = new ConfigController(
                mock(LlmChatClient.class), mock(ResourceAdvisorService.class),
                mock(EmbeddingConsistencyChecker.class), mock(EmbeddingReindexService.class),
                mock(RuntimeParamsMaterializer.class), ragService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void ragConfig_reportsPerModuleAvailability() throws Exception {
        Map<String, Boolean> avail = new LinkedHashMap<>();
        avail.put("rerank", true);
        avail.put("corrective", false);
        avail.put("hybrid", true);
        when(ragService.moduleAvailability()).thenReturn(avail);

        mockMvc.perform(get("/api/config/rag"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules.rerank").value(true))
                .andExpect(jsonPath("$.modules.corrective").value(false))
                .andExpect(jsonPath("$.modules.hybrid").value(true));
    }
}
