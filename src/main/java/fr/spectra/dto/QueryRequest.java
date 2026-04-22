package fr.spectra.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record QueryRequest(
        @NotBlank(message = "La question est obligatoire")
        String question,

        @Min(1) @Max(20)
        Integer maxContextChunks,

        /** Candidats ChromaDB avant re-ranking. Defaults to 20. */
        @Min(1) @Max(100)
        Integer topCandidates,

        String collection,

        /** Température de génération (0.0–2.0). Defaults to 0.7. */
        @DecimalMin("0.0") @DecimalMax("2.0")
        Float temperature,

        /** Top-P (nucleus sampling, 0.0–1.0). Defaults to 0.9. */
        @DecimalMin("0.0") @DecimalMax("1.0")
        Float topP
) {
    public QueryRequest {
        if (maxContextChunks == null) maxContextChunks = 5;
        if (topCandidates == null)    topCandidates = 20;
        if (topCandidates < maxContextChunks) topCandidates = maxContextChunks;
        if (temperature == null) temperature = 0.7f;
        if (topP == null)        topP = 0.9f;
    }
}
