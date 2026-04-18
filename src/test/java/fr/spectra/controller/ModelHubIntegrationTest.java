package fr.spectra.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.dto.LlmFitRecommendation;
import fr.spectra.service.LlmFitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration HTTP de ModelHubController via MockMvc.
 * LlmFitService est substitué par un @MockBean — aucun processus llmfit n'est lancé.
 */
@WebMvcTest(ModelHubController.class)
class ModelHubIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LlmFitService llmFitService;

    @Autowired
    private ObjectMapper objectMapper;

    // ── GET /api/models/hub/recommendations ───────────────────────────────────

    @Test
    void getRecommendations_defaultLimit_returns200WithJsonBody() throws Exception {
        when(llmFitService.getRecommendations(10, null, null, null))
                .thenReturn(new LlmFitRecommendation(List.of(), null));

        mockMvc.perform(get("/api/models/hub/recommendations"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.models").isArray());
    }

    @Test
    void getRecommendations_withModels_returnsModelDetails() throws Exception {
        LlmFitRecommendation.ModelRecommendation model = new LlmFitRecommendation.ModelRecommendation(
                "llama3.2:3b", "Meta", "3B", "chat", 0.87, "good", "Q4_K_M",
                12.5, 2.1, 1.8, 4096, List.of("Rapide sur CPU"),
                Map.of("fit", 0.9), List.of(), false, "CPU");
        when(llmFitService.getRecommendations(anyInt(), any(), any(), any()))
                .thenReturn(new LlmFitRecommendation(List.of(model), null));

        mockMvc.perform(get("/api/models/hub/recommendations").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models", hasSize(1)))
                .andExpect(jsonPath("$.models[0].name", is("llama3.2:3b")))
                .andExpect(jsonPath("$.models[0].provider", is("Meta")))
                .andExpect(jsonPath("$.models[0].fit_level", is("good")))
                .andExpect(jsonPath("$.models[0].best_quant", is("Q4_K_M")))
                .andExpect(jsonPath("$.models[0].run_mode", is("CPU")));
    }

    @Test
    void getRecommendations_withHardwareSimulation_passesParamsToService() throws Exception {
        when(llmFitService.getRecommendations(5, "8192", "16384", 4))
                .thenReturn(new LlmFitRecommendation(List.of(), null));

        mockMvc.perform(get("/api/models/hub/recommendations")
                        .param("limit", "5")
                        .param("memory", "8192")
                        .param("ram", "16384")
                        .param("cpuCores", "4"))
                .andExpect(status().isOk());

        verify(llmFitService).getRecommendations(5, "8192", "16384", 4);
    }

    @Test
    void getRecommendations_llmfitUnavailable_returnsEmptyModels() throws Exception {
        when(llmFitService.getRecommendations(anyInt(), any(), any(), any()))
                .thenReturn(new LlmFitRecommendation(List.of(), null));

        mockMvc.perform(get("/api/models/hub/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models").isEmpty());
    }

    @Test
    void getRecommendations_systemSpecs_returnedWhenPresent() throws Exception {
        LlmFitRecommendation.SystemSpecs specs = new LlmFitRecommendation.SystemSpecs(
                "Intel i7-13700K", 8, 16.0, 8.0, null, 0.0, false);
        when(llmFitService.getRecommendations(anyInt(), any(), any(), any()))
                .thenReturn(new LlmFitRecommendation(List.of(), specs));

        mockMvc.perform(get("/api/models/hub/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.system.cpu_cores", is(8)))
                .andExpect(jsonPath("$.system.total_ram_gb", is(16.0)))
                .andExpect(jsonPath("$.system.has_gpu", is(false)));
    }

    @Test
    void getRecommendations_multipleModels_orderPreserved() throws Exception {
        LlmFitRecommendation.ModelRecommendation m1 = new LlmFitRecommendation.ModelRecommendation(
                "model-a", "ProvA", "3B", "chat", 0.95, "perfect", "Q4_K_M",
                15.0, 2.0, 1.5, 4096, List.of(), Map.of(), List.of(), false, "CPU");
        LlmFitRecommendation.ModelRecommendation m2 = new LlmFitRecommendation.ModelRecommendation(
                "model-b", "ProvB", "7B", "chat", 0.80, "good", "Q5_K_M",
                8.0, 4.5, 4.0, 8192, List.of(), Map.of(), List.of(), false, "GPU");
        when(llmFitService.getRecommendations(anyInt(), any(), any(), any()))
                .thenReturn(new LlmFitRecommendation(List.of(m1, m2), null));

        mockMvc.perform(get("/api/models/hub/recommendations").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models", hasSize(2)))
                .andExpect(jsonPath("$.models[0].name", is("model-a")))
                .andExpect(jsonPath("$.models[1].name", is("model-b")));
    }

    // ── POST /api/models/hub/install ──────────────────────────────────────────

    @Test
    void installModel_validModelName_returns200WithInProgress() throws Exception {
        when(llmFitService.installModel("llama3.2:3b", null, false))
                .thenReturn(CompletableFuture.completedFuture(true));

        mockMvc.perform(post("/api/models/hub/install")
                        .param("modelName", "llama3.2:3b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("IN_PROGRESS")))
                .andExpect(jsonPath("$.modelName", is("llama3.2:3b")));
    }

    @Test
    void installModel_withQuant_passesQuantToService() throws Exception {
        when(llmFitService.installModel("phi-4", "Q4_K_M", false))
                .thenReturn(CompletableFuture.completedFuture(true));

        mockMvc.perform(post("/api/models/hub/install")
                        .param("modelName", "phi-4")
                        .param("quant", "Q4_K_M"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("IN_PROGRESS")));

        verify(llmFitService).installModel("phi-4", "Q4_K_M", false);
    }

    @Test
    void installModel_missingModelName_returns400() throws Exception {
        mockMvc.perform(post("/api/models/hub/install"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void installModel_noQuant_passesNullToService() throws Exception {
        when(llmFitService.installModel("mistral:7b", null, false))
                .thenReturn(CompletableFuture.completedFuture(true));

        mockMvc.perform(post("/api/models/hub/install")
                        .param("modelName", "mistral:7b"))
                .andExpect(status().isOk());

        verify(llmFitService).installModel("mistral:7b", null, false);
    }

    // ── flux recommandation → installation ────────────────────────────────────

    /**
     * Teste le flux complet côté HTTP :
     * 1. GET /recommendations pour découvrir les modèles disponibles
     * 2. Le client sélectionne le premier modèle (meilleur score)
     * 3. POST /install avec le nom et le quant issus de la recommandation
     *
     * Vérifie que les deux endpoints coopèrent correctement sans régression.
     */
    @Test
    void recommendThenInstall_fullHttpFlow_succeeds() throws Exception {
        LlmFitRecommendation.ModelRecommendation topModel = new LlmFitRecommendation.ModelRecommendation(
                "mistral:7b-q4km", "Mistral", "7B", "chat", 0.91, "perfect",
                "Q4_K_M", 8.3, 4.2, 3.9, 8192, List.of(), Map.of(), List.of(), false, "GPU");
        when(llmFitService.getRecommendations(anyInt(), any(), any(), any()))
                .thenReturn(new LlmFitRecommendation(List.of(topModel), null));
        when(llmFitService.installModel("mistral:7b-q4km", "Q4_K_M", false))
                .thenReturn(CompletableFuture.completedFuture(true));

        // Étape 1 : récupérer les recommandations
        String body = mockMvc.perform(get("/api/models/hub/recommendations").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0].name", is("mistral:7b-q4km")))
                .andReturn().getResponse().getContentAsString();

        LlmFitRecommendation recs = objectMapper.readValue(body, LlmFitRecommendation.class);
        String chosenModel = recs.models().get(0).name();
        String chosenQuant = recs.models().get(0).bestQuant();

        assertThat(chosenModel).isEqualTo("mistral:7b-q4km");
        assertThat(chosenQuant).isEqualTo("Q4_K_M");

        // Étape 2 : installer le modèle sélectionné
        mockMvc.perform(post("/api/models/hub/install")
                        .param("modelName", chosenModel)
                        .param("quant", chosenQuant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("IN_PROGRESS")))
                .andExpect(jsonPath("$.modelName", is("mistral:7b-q4km")));

        verify(llmFitService).installModel("mistral:7b-q4km", "Q4_K_M", false);
    }

    /**
     * Simule un matériel faible : aucun modèle ne rentre en mémoire.
     * Vérifie que le serveur renvoie une liste vide plutôt qu'une erreur.
     */
    @Test
    void recommendThenInstall_noFittingModels_clientReceivesEmptyListNot5xx() throws Exception {
        when(llmFitService.getRecommendations(anyInt(), eq("2048"), any(), any()))
                .thenReturn(new LlmFitRecommendation(List.of(), null));

        mockMvc.perform(get("/api/models/hub/recommendations")
                        .param("memory", "2048"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models").isEmpty());
    }
}
