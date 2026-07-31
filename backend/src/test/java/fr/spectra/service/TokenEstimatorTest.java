package fr.spectra.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariants de la conversion caractères ↔ tokens.
 *
 * <p>Cette classe existe parce que la grandeur était définie six fois dans le backend, avec
 * <b>deux valeurs différentes</b> : les budgets de contexte étaient <i>vérifiés</i> à 3,5
 * caractères par token et <i>dépensés</i> à 4. La marge de sécurité de 15 % était donc
 * consommée par le seul écart de taux de change, sans que rien ne le signale.</p>
 */
class TokenEstimatorTest {

    @Test
    void spendingABudgetNeverCostsMoreThanTheBudget() {
        // L'invariant qui était violé : ce qu'un budget autorise, recompté, doit y tenir.
        // Avec deux taux (4 pour convertir en caractères, 3,5 pour recompter), une dépense
        // de 1240 tokens en coûtait 1417 — 14 % de plus que ce qui avait été vérifié.
        for (int budget : new int[]{100, 256, 512, 1024, 1240, 3000, 8192}) {
            assertThat(TokenEstimator.estimateTokens(TokenEstimator.charsFor(budget)))
                    .as("budget de %d tokens", budget)
                    .isLessThanOrEqualTo(budget);
        }
    }

    @Test
    void theValidatorUsesTheSameRateAsEveryoneElse() {
        // Le contrôle des budgets et leur dépense doivent parler la même langue : c'est tout
        // l'objet du regroupement. Ce test tombe si quelqu'un redéfinit un taux localement.
        assertThat(ContextBudgetValidator.CHARS_PER_TOKEN)
                .isEqualTo(TokenEstimator.CHARS_PER_TOKEN);
    }

    @Test
    void estimateIsPessimisticForFrench() {
        // 3,5 et non 4 : le français se tokenize moins bien que l'anglais, et sous-estimer le
        // coût d'un texte est exactement l'erreur que les contrôles de budget doivent attraper.
        assertThat(TokenEstimator.CHARS_PER_TOKEN).isLessThan(4.0);
        assertThat(TokenEstimator.estimateTokens("x".repeat(3500))).isEqualTo(1000);
    }

    @Test
    void blankOrNullText_costsNothing() {
        assertThat(TokenEstimator.estimateTokens((String) null)).isZero();
        assertThat(TokenEstimator.estimateTokens("   ")).isZero();
    }

    @Test
    void aNonEmptyTextAlwaysCostsAtLeastOneToken() {
        // Sans ce plancher, un texte court serait gratuit et n'entrerait dans aucun budget.
        assertThat(TokenEstimator.estimateTokens("ok")).isEqualTo(1);
    }
}
