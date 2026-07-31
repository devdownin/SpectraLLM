package fr.spectra.service;

/**
 * Conversion caractères ↔ tokens, en un seul endroit.
 *
 * <p><b>Pourquoi cette classe existe.</b> Cette grandeur était définie cinq fois dans le
 * backend, avec <b>deux valeurs différentes</b> : 3,5 dans le contrôle des budgets de
 * contexte, 4 dans les deux services RAG et dans deux estimations de métriques. Ce n'était
 * pas qu'une redondance esthétique — le budget du RAG agentique était <i>vérifié</i> à 3,5 et
 * <i>dépensé</i> à 4, si bien que la marge de sécurité était consommée par le seul écart de
 * taux de change :</p>
 *
 * <pre>
 * fenêtre 2048 → marge sûre 1740 tokens → budget chunks 1240 (500 réservés à la réponse)
 *   caractères acceptés : 1240 × 4   = 4960
 *   coût réel           : 4960 ÷ 3,5 = 1417 tokens
 *   total réel          : 1417 + 500 = 1917, soit 94 % de la fenêtre au lieu des 85 % visés
 * </pre>
 *
 * <p><b>Pourquoi 3,5 et non 4.</b> Le produit traite des documents en français, qui se
 * tokenize moins bien que l'anglais. La valeur est volontairement pessimiste : sous-estimer
 * le coût d'un texte est exactement l'erreur que les contrôles de budget existent pour
 * attraper.</p>
 *
 * <p>Ce n'est qu'une <b>estimation</b> : la seule mesure exacte serait de tokenizer avec le
 * vocabulaire du modèle servi, ce qui supposerait de charger ce vocabulaire côté backend.
 * Pour décider si un texte tient dans une fenêtre, une borne pessimiste suffit.</p>
 */
public final class TokenEstimator {

    /** Caractères par token, en français. Volontairement pessimiste — voir la javadoc. */
    public static final double CHARS_PER_TOKEN = 3.5;

    private TokenEstimator() {}

    /** Coût estimé d'un texte, en tokens. {@code null} ou vide → 0. */
    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, estimateTokens(text.length()));
    }

    /** Coût estimé d'une longueur en caractères, en tokens. */
    public static int estimateTokens(int chars) {
        return (int) (chars / CHARS_PER_TOKEN);
    }

    /** Nombre de caractères qu'un budget de tokens peut porter — réciproque du précédent. */
    public static int charsFor(int tokens) {
        return (int) (tokens * CHARS_PER_TOKEN);
    }
}
