package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Confrontation des budgets de contexte à la fenêtre réellement servie.
 *
 * <p>Ce contrôle existe parce que le dépassement ne produit pas d'erreur franche : llama.cpp
 * tronque le début de la requête — le prompt système — et le modèle répond hors format. Les
 * cas ci-dessous reproduisent les configurations qui ont réellement échoué.</p>
 */
class ContextBudgetValidatorTest {

    /** Propriétés minimales, avec les seuls modules dont le budget nous intéresse. */
    private static SpectraProperties props(Integer excerptChars, Boolean agenticEnabled,
                                           Integer agenticTokens) {
        SpectraProperties p = mock(SpectraProperties.class);
        when(p.classification()).thenReturn(new SpectraProperties.ClassificationProperties(
                true, null, null, null, null, null, null, excerptChars, null));
        when(p.agenticRag()).thenReturn(new SpectraProperties.AgenticRagProperties(
                agenticEnabled, null, null, null, agenticTokens));
        when(p.longContextRag()).thenReturn(null);
        return p;
    }

    @Test
    void excerptThatOverflowsTheWindow_isReported() {
        // Le bug d'origine : 6000 caractères (~1715 tokens) + prompt, dans une fenêtre de 2048.
        List<String> problems = ContextBudgetValidator.problems(2048, props(6000, false, null));

        assertThat(problems).singleElement().asString()
                .contains("max-excerpt-chars = 6000")
                .contains("marge sûre");
    }

    @Test
    void sameExcerptFitsOnceTheWindowIsWideEnough() {
        // Même valeur, fenêtre de 4096 : c'est le correctif appliqué au dimensionnement.
        assertThat(ContextBudgetValidator.problems(4096, props(6000, false, null))).isEmpty();
    }

    @Test
    void reportSuggestsAWorkableValue_notJustAComplaint() {
        List<String> problems = ContextBudgetValidator.problems(2048, props(6000, false, null));

        // La suggestion doit être exploitable telle quelle : une valeur, pas « réduisez ».
        assertThat(problems.getFirst()).containsPattern("~\\d+ caractères");
    }

    @Test
    void disabledModulesAreNotJudged() {
        // Un module désactivé n'envoie rien au modèle : son budget est sans effet.
        SpectraProperties p = mock(SpectraProperties.class);
        when(p.classification()).thenReturn(new SpectraProperties.ClassificationProperties(
                false, null, null, null, null, null, null, 99_000, null));
        when(p.agenticRag()).thenReturn(new SpectraProperties.AgenticRagProperties(
                false, null, null, null, 99_000));
        when(p.longContextRag()).thenReturn(null);

        assertThat(ContextBudgetValidator.problems(2048, p)).isEmpty();
    }

    @Test
    void agenticBudgetIsCheckedWhenEnabled() {
        // 3000 tokens de contexte agentique dans une fenêtre de 2048 : impossible.
        List<String> problems = ContextBudgetValidator.problems(2048, props(1000, true, 3000));

        assertThat(problems).anySatisfy(p ->
                assertThat(p).contains("agentic-rag.max-context-tokens = 3000"));
    }

    @Test
    void agenticBudgetOfThreeThousandFitsAFourThousandWindow() {
        // Justification du choix de laisser ce budget à 3000 : il passe sur une machine 16 Go
        // (4096 tokens par requête), qui est le minimum documenté.
        assertThat(ContextBudgetValidator.problems(4096, props(1000, true, 3000))).isEmpty();
    }

    @Test
    void unknownWindow_yieldsNoVerdict() {
        // Sans information du serveur, on s'abstient plutôt que de supposer une fenêtre.
        assertThat(ContextBudgetValidator.problems(0, props(99_000, true, 99_000))).isEmpty();
    }

    @Test
    void marginIsKeptBelowTheHardLimit() {
        // Un budget qui remplit la fenêtre à 95 % est signalé : la réponse du modèle et les
        // variations de tokenization consomment ce qui reste.
        int window = 4096;
        int nearlyFull = (int) ((window - ContextBudgetValidator.CLASSIFICATION_OVERHEAD_TOKENS)
                * ContextBudgetValidator.CHARS_PER_TOKEN * 0.95);

        assertThat(ContextBudgetValidator.problems(window, props(nearlyFull, false, null)))
                .isNotEmpty();
    }
}
