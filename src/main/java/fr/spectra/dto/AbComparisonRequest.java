package fr.spectra.dto;

/**
 * Requête de comparaison directe A/B entre deux modèles.
 */
public record AbComparisonRequest(
        String modelA,
        String modelB,
        /** Taille du jeu de test. Si null, 5 % du dataset (min 5, max 50). */
        Integer testSetSize
) {
}
