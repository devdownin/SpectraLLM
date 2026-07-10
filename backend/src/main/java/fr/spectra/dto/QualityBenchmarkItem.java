package fr.spectra.dto;

/**
 * Résultat d'évaluation d'une question du benchmark qualité tenu à l'écart.
 *
 * <p>Pour une question <b>answerable</b>, {@code score} (1-10) note la fidélité/exactitude.
 * Pour une question <b>non answerable</b> (réponse absente du corpus), {@code refused} indique
 * si le modèle s'est correctement abstenu, et {@code hallucinated} l'inverse.</p>
 */
public record QualityBenchmarkItem(
        String question,
        String category,
        boolean answerable,
        String reference,
        String modelAnswer,
        Double score,
        Boolean refused,
        Boolean hallucinated,
        String judgeNote
) {}
