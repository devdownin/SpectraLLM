package fr.spectra.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ContextCompressionServiceTest {
    private final LlmChatClient llm = mock(LlmChatClient.class);
    private final ContextCompressionService service = new ContextCompressionService(llm);
    private final String original = "La valeur est 10.\nUne phrase inutile.\nLa limite est 20.";

    @Test void keepsVerbatimExcerptsInTheirOriginalOrder() {
        when(llm.chat(anyString(), anyString())).thenReturn("La valeur est 10.\nLa limite est 20.");
        assertThat(service.compress("valeur et limite", List.of(original)).compressedTexts())
                .containsExactly("La valeur est 10.\nLa limite est 20.");
    }

    @Test void fabricatedValueCannotBecomeDocumentaryEvidence() {
        when(llm.chat(anyString(), anyString())).thenReturn("La valeur est 999.");
        assertThat(service.compress("valeur", List.of(original)).compressedTexts()).containsExactly(original);
    }

    @Test void rejectsReorderedOrDuplicatedExcerpts() {
        when(llm.chat(anyString(), anyString()))
                .thenReturn("La limite est 20.\nLa valeur est 10.", "La valeur est 10.\nLa valeur est 10.");
        assertThat(service.compress("valeur", List.of(original)).compressedTexts()).containsExactly(original);
        assertThat(service.compress("valeur", List.of(original)).compressedTexts()).containsExactly(original);
    }

    @Test void irrelevantResultIsAnEmptyContext() {
        when(llm.chat(anyString(), anyString())).thenReturn("IRRELEVANT");
        var result = service.compress("autre sujet", List.of(original));
        assertThat(result.keptIndices()).isEmpty();
        assertThat(result.compressedTexts()).isEmpty();
    }

    @Test void technicalFailurePreservesTheOriginal() {
        when(llm.chat(anyString(), anyString())).thenThrow(new IllegalStateException("panne"));
        assertThat(service.compress("valeur", List.of(original)).compressedTexts()).containsExactly(original);
    }
}
