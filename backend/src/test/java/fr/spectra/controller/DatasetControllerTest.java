package fr.spectra.controller;

import fr.spectra.model.DpoPair;
import fr.spectra.service.dataset.DatasetExportService;
import fr.spectra.service.dataset.DatasetGeneratorService;
import fr.spectra.service.dataset.DpoGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tests des endpoints DPO préférences A/B et stats de {@link DatasetController}. */
class DatasetControllerTest {

    private MockMvc mockMvc;
    private DpoGenerationService dpoService;

    @BeforeEach
    void setUp() {
        dpoService = mock(DpoGenerationService.class);
        DatasetController controller = new DatasetController(
                mock(DatasetGeneratorService.class), mock(DatasetExportService.class), dpoService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void recordPreference_valid_returnsOkAndForwardsToService() throws Exception {
        DpoPair pair = new DpoPair("Q ?", "chosen", "rejected", "playground-ab", "ab:without-rerank");
        when(dpoService.addPreferencePair(eq("Q ?"), eq("chosen"), eq("rejected"), eq("ab:without-rerank")))
                .thenReturn(pair);
        when(dpoService.getAllPairs()).thenReturn(List.of(pair));
        when(dpoService.getPreferencePairs()).thenReturn(List.of(pair));

        mockMvc.perform(post("/api/dataset/dpo/preference")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"Q ?","chosen":"chosen","rejected":"rejected","source":"ab:without-rerank"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.totalPairs").value(1))
                .andExpect(jsonPath("$.preferencePairs").value(1));

        verify(dpoService).addPreferencePair(eq("Q ?"), eq("chosen"), eq("rejected"), eq("ab:without-rerank"));
    }

    @Test
    void recordPreference_invalid_returns400() throws Exception {
        when(dpoService.addPreferencePair(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(null);

        mockMvc.perform(post("/api/dataset/dpo/preference")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"Q","chosen":"same","rejected":"same","source":"ab:x"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void dpoStats_reportsGeneratedAndPreferenceBreakdown() throws Exception {
        DpoPair generated = new DpoPair("p", "c", "r", "cat", "gen");
        DpoPair preference = new DpoPair("p2", "c2", "r2", "playground-ab", "ab:without-hybrid");
        when(dpoService.getAllPairs()).thenReturn(List.of(generated, preference));
        when(dpoService.getPreferencePairs()).thenReturn(List.of(preference));

        mockMvc.perform(get("/api/dataset/dpo/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPairs").value(2))
                .andExpect(jsonPath("$.generatedPairs").value(1))
                .andExpect(jsonPath("$.preferencePairs").value(1))
                .andExpect(jsonPath("$.status").value("READY"));
    }
}
