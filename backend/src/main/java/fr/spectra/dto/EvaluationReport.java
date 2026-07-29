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
        /** Score moyen par nature d'exercice (qa, summary, negative…). */
        Map<String, Double> scoresByCategory,
        /**
         * Score moyen par catégorie de document (R8) — le diagnostic thématique : c'est ici
         * qu'on voit que le modèle est bon sur les procédures et faible sur la réglementation,
         * ce qui renvoie directement à la composition du corpus d'entraînement. Vide tant
         * qu'aucune paire évaluée ne provient d'un document classifié.
         */
        Map<String, Double> scoresByDocumentCategory,
        List<EvaluationScore> scores,
        /** Latence moyenne de génération par réponse, en millisecondes (modèle évalué). */
        double avgLatencyMs,
        /** Débit moyen en tokens/seconde (réel si fourni par llama.cpp, sinon estimé). */
        double avgTokensPerSec,
        String error,
        Instant startedAt,
        Instant completedAt,
        /** Modèle ayant servi de juge (le modèle évalué lui-même en auto-jugement). */
        String judgeModel
) {
    public static EvaluationReport pending(String evalId, String modelName, String jobId, String judgeModel) {
        return new EvaluationReport(
                evalId, "PENDING", modelName, jobId,
                0, 0, 0.0, Map.of(), Map.of(), List.of(), 0.0, 0.0, null,
                Instant.now(), null, judgeModel
        );
    }
}
