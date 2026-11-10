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
        when(llmClient.decide(anyString(), anyString()))
                .thenReturn("1: RELEVANT\n2: IRRELEVANT\n3: AMBIGUOUS");

        List<Integer> kept = service.gradeChunks("Question ?", List.of("a", "b", "c"));

        assertThat(kept).containsExactly(0, 2);
    }

    @Test
    void gradeChunks_llmFailure_keepsAllChunks() {
        when(llmClient.decide(anyString(), anyString())).thenThrow(new RuntimeException("LLM down"));

        List<Integer> kept = service.gradeChunks("Question ?", List.of("a", "b"));

        assertThat(kept).containsExactly(0, 1);
    }

    // ── reformulateQuery ──────────────────────────────────────────────────────

    @Test
    void reformulateQuery_nominal_returnsReformulation() {
        when(llmClient.decide(anyString(), anyString()))
                .thenReturn("Quelles règles de sécurité s'appliquent aux autoroutes ?");

        Optional<String> out = service.reformulateQuery("Normes autoroutes ?");

        assertThat(out).contains("Quelles règles de sécurité s'appliquent aux autoroutes ?");
    }

    @Test
    void reformulateQuery_llmFailure_returnsEmpty() {
        when(llmClient.decide(anyString(), anyString())).thenThrow(new RuntimeException("LLM down"));

        assertThat(service.reformulateQuery("Normes autoroutes ?")).isEmpty();
    }

    @Test
    void reformulateQuery_blankAnswer_returnsEmpty() {
        when(llmClient.decide(anyString(), anyString())).thenReturn("   ");

        assertThat(service.reformulateQuery("Normes autoroutes ?")).isEmpty();
    }

    @Test
    void reformulateQuery_identicalAnswer_returnsEmpty() {
        when(llmClient.decide(anyString(), anyString())).thenReturn("Normes autoroutes ?");

        assertThat(service.reformulateQuery("Normes autoroutes ?")).isEmpty();
    }

    // ── minRelevantChunks ─────────────────────────────────────────────────────

    @Test
    void minRelevantChunks_readFromProperties() {
        assertThat(service.minRelevantChunks()).isEqualTo(2);
    }

    @Test
    void gradeChunks_partialVerdicts_keepUngradedChunks_inRankOrder() {
        // Le modèle ne note que 2 passages sur 4, et dans le désordre : un passage sans verdict
        // n'a pas été jugé hors sujet, et l'ordre du classement d'entrée doit être conservé.
        when(llmClient.decide(anyString(), anyString())).thenReturn("3: RELEVANT\n1: IRRELEVANT");

        List<Integer> kept = service.gradeChunks("Question ?", List.of("a", "b", "c", "d"));

        assertThat(kept).containsExactly(1, 2, 3);
    }

    @Test
    void gradeChunks_allIrrelevant_staysEmpty() {
        when(llmClient.decide(anyString(), anyString())).thenReturn("1: IRRELEVANT\n2: IRRELEVANT");

        assertThat(service.gradeChunks("Question ?", List.of("a", "b"))).isEmpty();
    }

    @Test
    void documentsBlock_showsMoreOfEachPassage_butStaysUnderTheBudget() {
        String chunk = "x".repeat(3000);

        String few = service.buildDocumentsBlock(List.of(chunk, chunk, chunk));
        String many = service.buildDocumentsBlock(java.util.Collections.nCopies(20, chunk));

        // 3 passages : l'extrait par défaut (800) ; 20 passages : 8000 / 20 = 400, comme avant ce réglage.
        assertThat(few).contains("x".repeat(800) + "...").doesNotContain("x".repeat(801));
        assertThat(many.length()).isLessThan(CorrectiveRagService.MAX_BLOCK_CHARS + 20 * 40);
        assertThat(many).contains("x".repeat(400) + "...").doesNotContain("x".repeat(401));
        // Au-delà de 40 passages, le plancher s'applique.
        assertThat(service.buildDocumentsBlock(java.util.Collections.nCopies(60, chunk)))
                .contains("x".repeat(CorrectiveRagService.MIN_PREVIEW_CHARS) + "...")
                .doesNotContain("x".repeat(CorrectiveRagService.MIN_PREVIEW_CHARS + 1));
    }

    @Test
    void previewChars_isConfigurable_andBounded() {
        service.configurePreview(300);
        assertThat(service.buildDocumentsBlock(List.of("y".repeat(1000)))).contains("y".repeat(300) + "...")
                .doesNotContain("y".repeat(301));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.configurePreview(50))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
