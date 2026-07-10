package fr.spectra.dto;

/**
 * Requête de lancement d'une évaluation LLM-as-a-judge.
 */
public record EvaluationRequest(

        /** Nom du modèle à évaluer. Si null, utilise le modèle actif. */
        String modelName,

        /** Taille fixe du jeu de test. Si null, utilise 5 % du dataset (min 5, max 50). */
        Integer testSetSize,

        /** ID d'un job de fine-tuning associé (optionnel). */
        String jobId
) {
    public EvaluationRequest {
        // allow fully null construction
    }
}
