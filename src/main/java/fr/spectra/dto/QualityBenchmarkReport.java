package fr.spectra.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Rapport d'un passage du benchmark qualité sur un modèle donné.
 *
 * <ul>
 *   <li>{@code avgScore} — score moyen (1-10) sur les questions answerable (fidélité/exactitude).</li>
 *   <li>{@code hallucinationRate} — part des questions non-answerable où le modèle a inventé une
 *       réponse au lieu de s'abstenir (0 = parfait).</li>
 *   <li>{@code refusalAccuracy} — part des questions non-answerable correctement refusées.</li>
 * </ul>
 */
public record QualityBenchmarkReport(
        String model,
        int total,
        int answerableCount,
        int unanswerableCount,
        double avgScore,
        double hallucinationRate,
        double refusalAccuracy,
        Map<String, Double> scoresByCategory,
        List<QualityBenchmarkItem> items,
        Instant startedAt,
        Instant completedAt
) {}
