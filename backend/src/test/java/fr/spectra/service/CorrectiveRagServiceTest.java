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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests du Corrective RAG — grading des chunks et reformulation de la requête
 * (support du retrieval complémentaire quand le grading laisse trop peu de chunks).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CorrectiveRagServiceTest {

    @Mock private LlmChatClient llmClient;
    @Mock private SpectraProperties props;

    private CorrectiveRagService service;

    @BeforeEach
    void setUp() {
        when(props.correctiveRag()).thenReturn(
                new SpectraProperties.CorrectiveRagProperties(true, 2));
        service = new CorrectiveRagService(llmClient, props);
    }

    // ── gradeChunks ───────────────────────────────────────────────────────────

    @Test
    void gradeChunks_keepsRelevantAndAmbiguous_dropsIrrelevant() {
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn("1: RELEVANT\n2: IRRELEVANT\n3: AMBIGUOUS");

        List<Integer> kept = service.gradeChunks("Question ?", List.of("a", "b", "c"));

        assertThat(kept).containsExactly(0, 2);
    }

    @Test
    void gradeChunks_llmFailure_keepsAllChunks() {
        when(llmClient.chat(anyString(), anyString())).thenThrow(new RuntimeException("LLM down"));

        List<Integer> kept = service.gradeChunks("Question ?", List.of("a", "b"));

        assertThat(kept).containsExactly(0, 1);
    }

    // ── reformulateQuery ──────────────────────────────────────────────────────

    @Test
    void reformulateQuery_nominal_returnsReformulation() {
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn("Quelles règles de sécurité s'appliquent aux autoroutes ?");

        Optional<String> out = service.reformulateQuery("Normes autoroutes ?");

        assertThat(out).contains("Quelles règles de sécurité s'appliquent aux autoroutes ?");
    }

    @Test
    void reformulateQuery_llmFailure_returnsEmpty() {
        when(llmClient.chat(anyString(), anyString())).thenThrow(new RuntimeException("LLM down"));

        assertThat(service.reformulateQuery("Normes autoroutes ?")).isEmpty();
    }

    @Test
    void reformulateQuery_blankAnswer_returnsEmpty() {
        when(llmClient.chat(anyString(), anyString())).thenReturn("   ");

        assertThat(service.reformulateQuery("Normes autoroutes ?")).isEmpty();
    }

    @Test
    void reformulateQuery_identicalAnswer_returnsEmpty() {
        when(llmClient.chat(anyString(), anyString())).thenReturn("Normes autoroutes ?");

        assertThat(service.reformulateQuery("Normes autoroutes ?")).isEmpty();
    }

    // ── minRelevantChunks ─────────────────────────────────────────────────────

    @Test
    void minRelevantChunks_readFromProperties() {
        assertThat(service.minRelevantChunks()).isEqualTo(2);
    }
}
