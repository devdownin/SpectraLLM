package fr.spectra.dto;

import java.util.List;

/**
 * Vérification des citations d'une réponse : chaque phrase qui cite {@code [n]} est confrontée au
 * passage n par le cross-encoder (paire phrase → passage).
 *
 * <p><b>Ce que mesure le score.</b> Un cross-encoder de reranking juge la <i>pertinence</i> d'un
 * passage pour un texte, pas l'implication logique : une phrase qui contredit son passage sur un
 * chiffre peut rester bien notée. Le contrôle attrape donc d'abord les citations hors sujet ou
 * inventées (le cas le plus fréquent d'hallucination « sourcée »), pas toutes les erreurs.</p>
 *
 * @param citedSentences      phrases portant au moins une citation
 * @param supportedSentences  phrases dont un passage cité atteint le seuil
 * @param unsupportedSentences phrases dont aucun passage cité n'atteint le seuil
 * @param invalidCitations    citations vers un numéro absent du contexte
 * @param uncitedSentences    phrases sans citation (non notées : transitions, synthèse)
 * @param faithfulness        {@code supported / cited}, {@code null} si aucune phrase ne cite
 * @param minScore            seuil appliqué
 * @param checks              détail par phrase citée
 */
public record CitationReport(
        int citedSentences,
        int supportedSentences,
        int unsupportedSentences,
        int invalidCitations,
        int uncitedSentences,
        Double faithfulness,
        float minScore,
        List<SentenceCheck> checks
) {
    public enum Status { SUPPORTED, UNSUPPORTED, INVALID_CITATION }

    /**
     * @param citations numéros cités par la phrase, dans l'ordre
     * @param bestScore meilleur score cross-encoder parmi les passages cités valides ({@code null} si aucun)
     */
    public record SentenceCheck(String sentence, List<Integer> citations, Float bestScore, Status status) {}
}
