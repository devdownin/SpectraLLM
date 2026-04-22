package fr.spectra.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record FineTuningRequest(

        /** Nom du modèle résultant (ex: "spectra-highway"). */
        @NotBlank(message = "Le nom du modèle est obligatoire")
        String modelName,

        /** Modèle de base à fine-tuner (défaut: depuis la config). */
        @Pattern(regexp = "[\\w./@:-]*", message = "baseModel contient des caractères non autorisés")
        String baseModel,

        /** LoRA rank. */
        @Min(4) @Max(256)
        Integer loraRank,

        /** LoRA alpha. */
        @Min(1) @Max(512)
        Integer loraAlpha,

        /** Nombre d'époques d'entraînement. */
        @Min(1) @Max(50)
        Integer epochs,

        /** Learning rate. */
        Double learningRate,

        /** Score de confiance minimum pour filtrer les paires du dataset. */
        Double minConfidence,

        /** Activer le packing de séquences pour l'entraînement. */
        Boolean packingEnabled,

        /** Activer l'entraînement DPO (Direct Preference Optimization). */
        Boolean dpoEnabled
) {
    public FineTuningRequest {
        if (loraRank == null) loraRank = 64;
        if (loraAlpha == null) loraAlpha = 128;
        if (epochs == null) epochs = 3;
        if (learningRate == null) learningRate = 2e-4;
        if (minConfidence == null) minConfidence = 0.8;
        if (packingEnabled == null) packingEnabled = false;
        if (dpoEnabled == null) dpoEnabled = false;
    }
}
