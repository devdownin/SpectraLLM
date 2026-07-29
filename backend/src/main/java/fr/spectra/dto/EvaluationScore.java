package fr.spectra.dto;

/**
 * Score LLM-as-a-judge pour une paire question/réponse du jeu de test.
 *
 * @param category         nature de l'exercice évalué (qa, summary, negative…)
 * @param documentCategory catégorie du document dont la paire est issue (R8) ; {@code null}
 *                         si le document n'était pas classifié au moment de la génération.
 *                         C'est la dimension qui répond à « sur quel thème le modèle
 *                         est-il faible ? », là où {@code category} répond à « sur quel
 *                         type d'exercice ? ».
 */
public record EvaluationScore(
        String question,
        String referenceAnswer,
        String modelAnswer,
        double score,
        String justification,
        String category,
        String documentCategory,
        String source
) {}
