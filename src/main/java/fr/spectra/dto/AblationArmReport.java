package fr.spectra.dto;

/**
 * Rapport d'un <b>bras</b> (arm) d'ablation : une configuration testée sur le benchmark.
 *
 * <p>Combine les trois familles de mesures sur la même base de questions :</p>
 * <ul>
 *   <li>{@code quality} — qualité de génération (exactitude LLM-juge, hallucination, refus).</li>
 *   <li>{@code retrieval} — qualité de retrieval (Hit@k / MRR / Recall@k) ; nulle/0 si le bras
 *       n'utilise pas le RAG ou si le benchmark n'annote pas {@code expectedSources}.</li>
 *   <li>{@code avgLatencyMs} / {@code p50LatencyMs} — coût en latence bout en bout.</li>
 * </ul>
 *
 * <p>Le gain marginal d'un enrichissement se lit en comparant deux bras (delta des champs).</p>
 */
public record AblationArmReport(
        String label,
        String model,
        boolean useRag,
        QualityBenchmarkReport quality,
        RetrievalMetrics retrieval,
        double avgLatencyMs,
        double p50LatencyMs
) {}
