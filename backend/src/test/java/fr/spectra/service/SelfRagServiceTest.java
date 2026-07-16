package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests du Self-RAG — notamment la propagation des paramètres de génération :
 * les appels de GÉNÉRATION doivent respecter temperature/topP de la requête
 * (régression : ils retombaient silencieusement sur les défauts du provider).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SelfRagServiceTest {

    private static final String GOOD_EVAL = "ISREL: RELEVANT\nISSUP: FULLY_SUPPORTED\nISUSE: USEFUL";
    private static final String BAD_EVAL  = "ISREL: RELEVANT\nISSUP: NO_SUPPORT\nISUSE: NOT_USEFUL";

    @Mock private LlmChatClient llmClient;
    @Mock private SpectraProperties props;

    private SelfRagService service;

    @BeforeEach
    void setUp() {
        when(props.selfRag()).thenReturn(null); // défaut : 1 itération de raffinement max
        service = new SelfRagService(llmClient, props);
    }

    @Test
    void reflect_generationUsesRequestTemperatureAndTopP() {
        when(llmClient.chat(anyString(), anyString(), anyFloat(), anyFloat())).thenReturn("Réponse");
        when(llmClient.chat(anyString(), anyString())).thenReturn(GOOD_EVAL);

        SelfRagService.SelfRagResult result =
                service.reflect("Question ?", List.of("chunk"), "sys-prompt", "user-msg", 0.3f, 0.5f);

        assertThat(result.answer()).isEqualTo("Réponse");
        assertThat(result.reflectionApplied()).isFalse();
        verify(llmClient).chat(eq("sys-prompt"), eq("user-msg"), eq(0.3f), eq(0.5f));
    }

    @Test
    void reflect_refinementAlsoUsesRequestTemperatureAndTopP() {
        when(llmClient.chat(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn("Réponse initiale", "Réponse raffinée");
        when(llmClient.chat(anyString(), anyString())).thenReturn(BAD_EVAL, GOOD_EVAL);

        SelfRagService.SelfRagResult result =
                service.reflect("Question ?", List.of("chunk"), "sys-prompt", "user-msg", 0.3f, 0.5f);

        assertThat(result.answer()).isEqualTo("Réponse raffinée");
        assertThat(result.reflectionApplied()).isTrue();
        // Les deux générations (initiale + raffinement) portent les paramètres de la requête.
        verify(llmClient, times(2)).chat(anyString(), eq("user-msg"), eq(0.3f), eq(0.5f));
    }
}
