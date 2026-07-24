package fr.spectra.service;

import fr.spectra.dto.ConversationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests du Conversational RAG — en particulier la dégradation gracieuse :
 * un échec LLM sur la reformulation ne doit PAS faire échouer la requête,
 * mais retomber sur la question originale (comme les autres modules optionnels).
 */
@ExtendWith(MockitoExtension.class)
class ConversationalRagServiceTest {

    private static final String QUESTION = "Et pour les tunnels ?";

    @Mock private LlmChatClient llmClient;

    private ConversationalRagService service;

    @BeforeEach
    void setUp() {
        service = new ConversationalRagService(llmClient);
    }

    private static List<ConversationMessage> history() {
        return List.of(
                new ConversationMessage("user", "Quelles sont les normes de sécurité autoroutières ?"),
                new ConversationMessage("assistant", "Les normes couvrent la signalisation et les glissières."));
    }

    @Test
    void contextualize_nominal_returnsReformulatedQuestion() {
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn("Quelles sont les normes de sécurité pour les tunnels autoroutiers ?");

        String out = service.contextualizeQuestion(QUESTION, history());

        assertThat(out).isEqualTo("Quelles sont les normes de sécurité pour les tunnels autoroutiers ?");
    }

    @Test
    void contextualize_llmFailure_fallsBackToOriginalQuestion() {
        when(llmClient.chat(anyString(), anyString()))
                .thenThrow(new RuntimeException("LLM indisponible"));

        String out = service.contextualizeQuestion(QUESTION, history());

        assertThat(out).isEqualTo(QUESTION);
    }

    @Test
    void contextualize_blankReformulation_fallsBackToOriginalQuestion() {
        when(llmClient.chat(anyString(), anyString())).thenReturn("   ");

        String out = service.contextualizeQuestion(QUESTION, history());

        assertThat(out).isEqualTo(QUESTION);
    }

    @Test
    void contextualize_emptyHistory_returnsOriginalWithoutLlmCall() {
        String out = service.contextualizeQuestion(QUESTION, List.of());

        assertThat(out).isEqualTo(QUESTION);
    }
}
