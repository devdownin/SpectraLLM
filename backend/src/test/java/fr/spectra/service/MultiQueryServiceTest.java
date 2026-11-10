package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiQueryServiceTest {

    private final LlmChatClient llm = mock(LlmChatClient.class);
    private final MultiQueryService service = new MultiQueryService(llm, mock(SpectraProperties.class));

    @Test
    void variantes_sansNumerotationNiDoublon_questionOriginaleEnTete() {
        when(llm.decide(anyString(), anyString())).thenReturn(
                "1. What is the upload limit?\n- Maximum file size per request\n2) What is the upload limit?\n\n");

        assertThat(service.generateQueries("Upload size limit?")).containsExactly(
                "Upload size limit?", "What is the upload limit?", "Maximum file size per request");
    }

    @Test
    void laConsigneDemandeLaLangueDeLaQuestion_etLAppelEstUneDecision() {
        when(llm.decide(anyString(), anyString())).thenReturn("Autre formulation");
        service.generateQueries("Upload size limit?");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).decide(anyString(), prompt.capture());
        assertThat(prompt.getValue()).contains("DANS LA LANGUE DE LA QUESTION").contains("Upload size limit?");
    }

    @Test
    void marqueursDeListe_retiresSansToucherAuTexte() {
        assertThat(MultiQueryService.stripListMarker("  3. Quelle limite ?")).isEqualTo("Quelle limite ?");
        assertThat(MultiQueryService.stripListMarker("• Quelle limite ?")).isEqualTo("Quelle limite ?");
        assertThat(MultiQueryService.stripListMarker("2024 budget figures")).isEqualTo("2024 budget figures");
        assertThat(MultiQueryService.stripListMarker("-5 °C threshold")).isEqualTo("-5 °C threshold");
    }

    @Test
    void panneDuModele_questionOriginaleSeule() {
        when(llm.decide(anyString(), anyString())).thenThrow(new IllegalStateException("LLM down"));
        assertThat(service.generateQueries("Q ?")).containsExactly("Q ?");
    }
}
