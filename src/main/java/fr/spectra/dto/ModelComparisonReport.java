package fr.spectra.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Rapport de comparaison entre plusieurs modèles LLM personnalisés.
 *
 * <p>Agrège plusieurs rapports d'évaluation LLM-as-a-judge ({@link EvaluationReport})
 * pour mesurer les gains et différences de performance d'un modèle à l'autre :
 * <ul>
 *   <li>delta du score global et par catégorie vs un modèle de référence (baseline) ;</li>
 *   <li>rattachement aux documents qui ont nourri chaque modèle (liens GED
 *       {@code TRAINED_ON} / {@code EVALUATED_ON}).</li>
 * </ul>
 * Les entrées sont triées par score global décroissant (classement).
 */
public record ModelComparisonReport(
        /** Nom du modèle de référence servant de base aux deltas. */
        String baselineModel,
        /** Union ordonnée des catégories présentes dans les modèles comparés. */
        List<String> categories,
        /** Une entrée par modèle comparé, classées par score décroissant. */
        List<ModelComparisonEntry> models
) {

    /** Performance d'un modèle dans la comparaison, avec son delta vs la baseline. */
    public record ModelComparisonEntry(
            String evalId,
            String modelName,
            String status,
            int processed,
            double averageScore,
            Map<String, Double> scoresByCategory,
            Instant completedAt,
            /** Latence moyenne de génération par réponse (ms). */
            double avgLatencyMs,
            /** Débit moyen estimé (tokens/seconde). */
            double avgTokensPerSec,
            /** Écart-type des scores par paire (dispersion). */
            double stdDev,
            /** Demi-largeur de l'intervalle de confiance à 95 % du score moyen. */
            double ci95,
            /** Nombre de documents ayant entraîné ce modèle (lien GED TRAINED_ON). */
            long trainedOnDocs,
            /** Nombre de documents ayant servi à évaluer ce modèle (lien GED EVALUATED_ON). */
            long evaluatedOnDocs,
            /** Vrai si ce modèle est la référence (baseline) de la comparaison. */
            boolean baseline,
            /** Écart de score global vs la baseline (positif = gain). */
            double deltaVsBaseline,
            /** Vrai si l'écart vs la baseline est statistiquement significatif (≈ 95 %). */
            boolean significantVsBaseline,
            /** Écart de score par catégorie vs la baseline (catégories communes uniquement). */
            Map<String, Double> deltaByCategory
    ) {}
}
