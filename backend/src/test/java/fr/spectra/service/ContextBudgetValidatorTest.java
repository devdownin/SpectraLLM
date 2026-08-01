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

    // ── Bornage des budgets exprimés en tokens (RAG agentique, long-contexte) ─────────────

    @Test
    void contextBudget_isClampedToTheServedWindow() {
        // Ces modules découpent déjà fidèlement leur contexte au budget qu'on leur donne.
        // Le défaut n'était pas l'absence de découpe, mais de découper à une taille que la
        // fenêtre ne peut pas porter : 3000 tokens respectés à la lettre débordent de 2048.
        LlmChatClient client = mock(LlmChatClient.class);
        when(client.servedContextTokens()).thenReturn(java.util.OptionalInt.of(2048));

        int clamped = ContextBudgetValidator.clampContextTokens(3000, client, "spectra.agentic-rag.max-context-tokens");

        assertThat(clamped).isLessThan(3000);
        assertThat(clamped).isEqualTo(ContextBudgetValidator.affordableContextTokens(2048));
    }

    @Test
    void contextBudget_isLeftAloneWhenItAlreadyFits() {
        LlmChatClient client = mock(LlmChatClient.class);
        when(client.servedContextTokens()).thenReturn(java.util.OptionalInt.of(8192));

        assertThat(ContextBudgetValidator.clampContextTokens(3000, client, "spectra.agentic-rag.max-context-tokens")).isEqualTo(3000);
    }

    @Test
    void contextBudget_unknownWindow_appliesTheConfiguredValue() {
        // Sans information du serveur, on ne restreint pas sur la foi d'une fenêtre supposée.
        LlmChatClient client = mock(LlmChatClient.class);
        when(client.servedContextTokens()).thenReturn(java.util.OptionalInt.empty());

        assertThat(ContextBudgetValidator.clampContextTokens(3000, client, "spectra.agentic-rag.max-context-tokens")).isEqualTo(3000);
    }

    @Test
    void unknownWindow_yieldsAnUnboundedTokenBudget_soCallersCanClampUnconditionally() {
        // Contrat de affordableContextTokens : MAX_VALUE quand la fenêtre est inconnue, ce qui
        // permet un Math.min sans condition côté appelant.
        assertThat(ContextBudgetValidator.affordableContextTokens(0)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void excerptChars_doNotOverflowOnAnUnknownWindow() {
        // affordableContextTokens rend MAX_VALUE ; converti en caractères sans garde, cela
        // débordait. Les appelants garantissent window > 0, mais pas les futurs.
        assertThat(ContextBudgetValidator.affordableExcerptChars(0)).isPositive();
    }

    @Test
    void theTwoBudgetsAgreeOnWhatFits() {
        // L'extrait de classification et les budgets en tokens dérivent de la même marge :
        // deux notions de « ce qui tient » donneraient deux diagnostics contradictoires sur
        // une même machine.
        int window = 4096;
        int tokensForExcerpt = (int) (ContextBudgetValidator.affordableExcerptChars(window)
                / ContextBudgetValidator.CHARS_PER_TOKEN)
                + ContextBudgetValidator.CLASSIFICATION_OVERHEAD_TOKENS;

        assertThat(tokensForExcerpt)
                .isLessThanOrEqualTo(ContextBudgetValidator.affordableContextTokens(window));
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
