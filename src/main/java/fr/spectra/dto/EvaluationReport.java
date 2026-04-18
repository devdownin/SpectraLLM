package fr.spectra.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Rapport d'évaluation LLM-as-a-judge.
 */
public record EvaluationReport(
        String evalId,
        /** PENDING | RUNNING | COMPLETED | FAILED */
        String status,
        String modelName,
        String jobId,
        int testSetSize,
        int processed,
        double averageScore,
        Map<String, Double> scoresByCategory,
        List<EvaluationScore> scores,
        String error,
        Instant startedAt,
        Instant completedAt
) {
    public static EvaluationReport pending(String evalId, String modelName, String jobId) {
        return new EvaluationReport(
                evalId, "PENDING", modelName, jobId,
                0, 0, 0.0, Map.of(), List.of(), null,
                Instant.now(), null
        );
    }
}
