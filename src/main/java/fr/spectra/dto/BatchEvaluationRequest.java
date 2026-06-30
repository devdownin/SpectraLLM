package fr.spectra.dto;

import java.util.List;

/**
 * Requête d'évaluation par lot : évalue plusieurs modèles sur un même jeu de test
 * pour comparer équitablement leurs gains.
 */
public record BatchEvaluationRequest(
        /** Modèles à évaluer séquentiellement (doublons ignorés, ordre conservé). */
        List<String> modelNames,

        /** Taille du jeu de test commun. Si null, 5 % du dataset (min 5, max 50). */
        Integer testSetSize
) {
}
