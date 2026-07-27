package fr.spectra.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record QueryRequest(
        @NotBlank(message = "La question est obligatoire")
        String question,

        @Min(1) @Max(20)
        Integer maxContextChunks,

        /** Candidats ChromaDB avant re-ranking. Defaults to 20. */
        @Min(1) @Max(100)
        Integer topCandidates,

        String collection,

        /**
         * Température de génération (0.0–2.0). {@code null} = non imposée par l'appelant :
         * le paramètre du modèle actif s'applique, sinon le défaut Spectra (0.7).
         */
        @DecimalMin("0.0") @DecimalMax("2.0")
        Float temperature,

        /**
         * Top-P (nucleus sampling, 0.0–1.0). {@code null} = non imposé par l'appelant :
         * le paramètre du modèle actif s'applique, sinon le défaut Spectra (0.9).
         */
        @DecimalMin("0.0") @DecimalMax("1.0")
        Float topP,

        /**
         * Historique de conversation pour le Conversational RAG.
         * {@code null} ou liste vide = pas de contexte conversationnel.
         */
        List<ConversationMessage> conversationHistory,

        /**
         * Active ou désactive le RAG (retrieval vectoriel ChromaDB).
         * {@code null} ou {@code true} = RAG actif (comportement par défaut).
         * {@code false} = réponse directe du LLM, sans retrieval.
         */
        Boolean useRag,

        /**
         * Surcharges par requête des modules RAG optionnels (rerank, corrective, multi-query…).
         * {@code null} = comportement de déploiement par défaut ({@link RagOverrides#NONE}).
         * Permet au Playground de désactiver un module pour une requête (toggles UI, comparaison A/B)
         * sans redéploiement. Un module absent du déploiement ne peut pas être activé par ce biais.
         */
        RagOverrides overrides
) {
    public QueryRequest {
        if (maxContextChunks == null) maxContextChunks = 5;
        if (topCandidates == null)    topCandidates = 20;
        if (topCandidates < maxContextChunks) topCandidates = maxContextChunks;
        // temperature / topP ne sont volontairement PAS défaultés ici : un null doit rester
        // distinguable d'une valeur explicite, sans quoi les paramètres du modèle actif
        // (registre) seraient systématiquement masqués par un défaut du DTO. Ils sont arbitrés
        // à l'entrée du pipeline RAG (cf. ActiveModelProfileService.ModelProfile#applyTo).
        if (useRag == null)      useRag = true;
        if (overrides == null)   overrides = RagOverrides.NONE;
    }

    /**
     * Copie de la requête dont les paramètres de génération sont figés sur les valeurs
     * effectives (déjà arbitrées entre requête, modèle actif et défauts Spectra). Permet au
     * pipeline de propager une requête dont {@code temperature}/{@code topP} ne sont plus
     * jamais {@code null}.
     */
    public QueryRequest withGenerationParams(float resolvedTemperature, float resolvedTopP) {
        return new QueryRequest(question, maxContextChunks, topCandidates, collection,
                resolvedTemperature, resolvedTopP, conversationHistory, useRag, overrides);
    }

    /** Constructeur de compatibilité (sans surcharges RAG) — {@link RagOverrides#NONE} par défaut. */
    public QueryRequest(String question, Integer maxContextChunks, Integer topCandidates,
                        String collection, Float temperature, Float topP,
                        List<ConversationMessage> conversationHistory, Boolean useRag) {
        this(question, maxContextChunks, topCandidates, collection, temperature, topP,
                conversationHistory, useRag, null);
    }
}
