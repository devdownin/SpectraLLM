package fr.spectra.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record QueryRequest(
        @NotBlank(message = "La question est obligatoire")
        String question,

        @Min(1) @Max(20)
        Integer maxContextChunks,

        /**
         * Number of candidates retrieved from ChromaDB before re-ranking.
         * Only used when the reranker is enabled. Must be >= maxContextChunks.
         * Defaults to 20.
         */
        @Min(1) @Max(100)
        Integer topCandidates,

        String collection
) {
    public QueryRequest {
        if (maxContextChunks == null) {
            maxContextChunks = 5;
        }
        if (topCandidates == null) {
            topCandidates = 20;
        }
        if (topCandidates < maxContextChunks) {
            topCandidates = maxContextChunks;
        }
    }
}
