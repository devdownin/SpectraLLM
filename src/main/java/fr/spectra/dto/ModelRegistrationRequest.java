package fr.spectra.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Demande d'enregistrement d'un modele dans le registre local Spectra.
 */
public record ModelRegistrationRequest(
        @NotBlank(message = "Le nom du modèle est obligatoire")
        String name,

        /**
         * Type du modele logique : `chat` ou `embedding`.
         */
        String type,

        /**
         * Source du modele : alias logique, chemin GGUF, repertoire, etc.
         */
        @NotBlank(message = "La source du modèle est obligatoire")
        String source,

        /**
         * Prompt systeme optionnel pour les modeles de chat.
         */
        String systemPrompt,

        /**
         * Parametres optionnels du modele.
         */
        Map<String, Object> parameters,

        /**
         * Active le modele juste apres son enregistrement.
         */
        Boolean activate,

        /**
         * Repo HuggingFace d'origine (optionnel, traçabilité).
         */
        String hfRepo,

        /**
         * Quantisation du GGUF (optionnel, ex. Q4_K_M).
         */
        String quantization,

        /**
         * Fenêtre de contexte d'entraînement n_ctx_train (optionnel).
         */
        Integer contextLength
) {
    public ModelRegistrationRequest {
        if (type == null || type.isBlank()) type = "chat";
        if (parameters == null) parameters = Map.of();
        if (activate == null) activate = Boolean.FALSE;
    }
}
