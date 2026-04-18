package fr.spectra.dto;

import java.util.List;

public record QueryResponse(
        String answer,
        List<Source> sources,
        long durationMs,
        boolean rerankApplied,
        boolean hybridSearchApplied,
        boolean agenticApplied,
        int agenticIterations,
        AgenticStopReason agenticStopReason
) {
    /** Raison d'arrêt de la boucle agentique. {@code null} si {@code agenticApplied == false}. */
    public enum AgenticStopReason {
        /** Le LLM a émis ACTION: ANSWER — arrêt normal. */
        ANSWER,
        /** Budget d'itérations épuisé — réponse forcée sur le contexte cumulé. */
        MAX_ITERATIONS,
        /** Recherche complémentaire n'a retourné aucun chunk nouveau. */
        NO_NEW_CHUNKS,
        /** Le LLM n'a pas respecté le format THOUGHT/ACTION. */
        FORMAT_ERROR
    }

    /** Backwards-compatible constructor without rerank/hybrid/agentic flags. */
    public QueryResponse(String answer, List<Source> sources, long durationMs) {
        this(answer, sources, durationMs, false, false, false, 0, null);
    }

    /** Backwards-compatible constructor with rerankApplied only. */
    public QueryResponse(String answer, List<Source> sources, long durationMs, boolean rerankApplied) {
        this(answer, sources, durationMs, rerankApplied, false, false, 0, null);
    }

    /** Backwards-compatible constructor without agentic fields. */
    public QueryResponse(String answer, List<Source> sources, long durationMs,
                         boolean rerankApplied, boolean hybridSearchApplied) {
        this(answer, sources, durationMs, rerankApplied, hybridSearchApplied, false, 0, null);
    }

    public record Source(
            String text,
            String sourceFile,
            double distance,
            Float rerankScore,
            Float bm25Score
    ) {
        /** Backwards-compatible constructor without rerankScore/bm25Score. */
        public Source(String text, String sourceFile, double distance) {
            this(text, sourceFile, distance, null, null);
        }

        /** Backwards-compatible constructor without bm25Score. */
        public Source(String text, String sourceFile, double distance, Float rerankScore) {
            this(text, sourceFile, distance, rerankScore, null);
        }
    }
}
