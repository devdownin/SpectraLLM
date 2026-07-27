package fr.spectra.controller;

import fr.spectra.service.ActiveModelProfileService;
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

/**
 * Tests des endpoints de configuration : GET /api/config/rag (disponibilité serveur des
 * modules) et GET /api/config/model (modèle actif + profil d'inférence effectif).
 */
class ConfigControllerTest {

    private MockMvc mockMvc;
    private RagService ragService;
    private LlmChatClient chatClient;
    private ActiveModelProfileService activeModelProfile;

    @BeforeEach
    void setUp() {
        ragService = mock(RagService.class);
        chatClient = mock(LlmChatClient.class);
        activeModelProfile = mock(ActiveModelProfileService.class);
        ConfigController controller = new ConfigController(
                chatClient, mock(ResourceAdvisorService.class),
                mock(EmbeddingConsistencyChecker.class), mock(EmbeddingReindexService.class),
                mock(RuntimeParamsMaterializer.class), ragService, activeModelProfile);
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

    @Test
    void getModel_exposesRegisteredPersonaAndParameters() throws Exception {
        when(chatClient.getActiveModel()).thenReturn("mon-ft");
        when(activeModelProfile.current()).thenReturn(new ActiveModelProfileService.ModelProfile(
                "mon-ft", "Tu es un juriste.", 0.15f, 0.5f));

        mockMvc.perform(get("/api/config/model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("mon-ft"))
                .andExpect(jsonPath("$.systemPrompt").value("Tu es un juriste."))
                .andExpect(jsonPath("$.personaSource").value("model"))
                .andExpect(jsonPath("$.parameters.temperature").value(0.15))
                .andExpect(jsonPath("$.parameters.topP").value(0.5));
    }

    @Test
    void getModel_withoutRegisteredProfile_reportsSpectraDefaults() throws Exception {
        when(chatClient.getActiveModel()).thenReturn("base");
        when(activeModelProfile.current())
                .thenReturn(ActiveModelProfileService.ModelProfile.DEFAULT);

        mockMvc.perform(get("/api/config/model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("base"))
                .andExpect(jsonPath("$.personaSource").value("spectra-default"))
                .andExpect(jsonPath("$.systemPrompt")
                        .value(fr.spectra.model.AssistantPersona.SYSTEM_PROMPT))
                .andExpect(jsonPath("$.parameters.temperature").value(0.7))
                .andExpect(jsonPath("$.parameters.topP").value(0.9));
    }
}
