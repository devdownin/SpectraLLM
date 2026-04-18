package fr.spectra.controller;

import fr.spectra.dto.LlmFitRecommendation;
import fr.spectra.service.LlmFitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires de ModelHubController — LlmFitService est mocké,
 * aucun contexte Spring n'est chargé.
 */
class ModelHubControllerTest {

    private LlmFitService llmFitService;
    private ModelHubController controller;

    @BeforeEach
    void setUp() {
        llmFitService = mock(LlmFitService.class);
        controller = new ModelHubController(llmFitService);
    }

    // ── getRecommendations ────────────────────────────────────────────────────

    @Test
    void getRecommendations_defaultParams_delegatesToService() {
        LlmFitRecommendation expected = new LlmFitRecommendation(List.of(), null);
        when(llmFitService.getRecommendations(10, null, null, null)).thenReturn(expected);

        LlmFitRecommendation result = controller.getRecommendations(10, null, null, null);

        assertThat(result).isSameAs(expected);
        verify(llmFitService).getRecommendations(10, null, null, null);
    }

    @Test
    void getRecommendations_withHardwareSimulation_passesAllParams() {
        when(llmFitService.getRecommendations(5, "8192", "16384", 8))
                .thenReturn(new LlmFitRecommendation(List.of(), null));

        controller.getRecommendations(5, "8192", "16384", 8);

        verify(llmFitService).getRecommendations(5, "8192", "16384", 8);
    }

    @Test
    void getRecommendations_serviceReturnsModels_propagatesListIntact() {
        LlmFitRecommendation.ModelRecommendation model = new LlmFitRecommendation.ModelRecommendation(
                "llama3.2:3b", "Meta", "3B", "chat", 0.87, "good", "Q4_K_M",
                12.5, 2.1, 1.8, 4096, List.of(), Map.of(), List.of(), false, "CPU");
        when(llmFitService.getRecommendations(anyInt(), any(), any(), any()))
                .thenReturn(new LlmFitRecommendation(List.of(model), null));

        LlmFitRecommendation result = controller.getRecommendations(10, null, null, null);

        assertThat(result.models()).hasSize(1);
        assertThat(result.models().get(0).name()).isEqualTo("llama3.2:3b");
        assertThat(result.models().get(0).fitLevel()).isEqualTo("good");
    }

    @Test
    void getRecommendations_llmfitUnavailable_returnsEmptyList() {
        when(llmFitService.getRecommendations(anyInt(), any(), any(), any()))
                .thenReturn(new LlmFitRecommendation(List.of(), null));

        LlmFitRecommendation result = controller.getRecommendations(10, null, null, null);

        assertThat(result.models()).isEmpty();
    }

    // ── installModel ──────────────────────────────────────────────────────────

    @Test
    void installModel_validModelName_returnsInProgressStatus() {
        when(llmFitService.installModel("llama3.2:3b", null, false))
                .thenReturn(CompletableFuture.completedFuture(true));

        Map<String, String> result = controller.installModel("llama3.2:3b", null);

        assertThat(result).containsEntry("status", "IN_PROGRESS");
        assertThat(result).containsEntry("modelName", "llama3.2:3b");
    }

    @Test
    void installModel_withQuant_passesQuantToService() {
        when(llmFitService.installModel(anyString(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(true));

        controller.installModel("phi-4", "Q4_K_M");

        verify(llmFitService).installModel("phi-4", "Q4_K_M", false);
    }

    @Test
    void installModel_noQuant_passesNullToService() {
        when(llmFitService.installModel(anyString(), isNull(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(true));

        controller.installModel("mistral:7b", null);

        verify(llmFitService).installModel("mistral:7b", null, false);
    }

    /**
     * Le frontend expose un toggle "Auto-Activation" mais le contrôleur
     * force autoActivate=false sans exposer ce paramètre côté HTTP.
     * Ce test documente ce comportement actuel.
     */
    @Test
    void installModel_autoActivateHardcodedFalse_regardlessOfIntent() {
        when(llmFitService.installModel(any(), any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(true));

        controller.installModel("any-model", null);

        verify(llmFitService).installModel(anyString(), any(), eq(false));
    }
}
