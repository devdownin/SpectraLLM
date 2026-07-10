package fr.spectra.dto;

/**
 * Score LLM-as-a-judge pour une paire question/réponse du jeu de test.
 */
public record EvaluationScore(
        String question,
        String referenceAnswer,
        String modelAnswer,
        double score,
        String justification,
        String category,
        String source
) {}
