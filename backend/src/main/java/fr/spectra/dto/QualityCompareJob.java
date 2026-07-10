package fr.spectra.dto;

import java.time.Instant;

/**
 * Suivi d'une comparaison qualité asynchrone entre deux modèles sur le benchmark tenu à l'écart.
 *
 * <p>Le benchmark qualité ({@code QualityBenchmarkService.run}) est bloquant et lent sur CPU
 * (deux appels LLM par question, ×2 modèles) : le lancer en synchrone depuis l'UI tiendrait
 * une requête HTTP ouverte plusieurs minutes. On le pilote donc comme un job asynchrone suivi
 * (statut + rapports partiels), à l'image de {@code EvaluationService}.</p>
 *
 * <p>Boucle « comparatif → qualité mesurée » : après installation + activation d'un modèle depuis
 * le Model Hub, ce job compare le nouveau modèle ({@code candidate}) au précédent ({@code baseline})
 * sur <b>votre corpus</b>, pour choisir sur des chiffres mesurés et non seulement sur le score de
 * compatibilité matérielle de llmfit.</p>
 */
public record QualityCompareJob(
        String jobId,
        Status status,
        String baseline,
        String candidate,
        String currentStep,
        QualityBenchmarkReport baselineReport,
        QualityBenchmarkReport candidateReport,
        String error,
        Instant createdAt,
        Instant completedAt
) {
    public enum Status {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    public static QualityCompareJob pending(String jobId, String baseline, String candidate) {
        return new QualityCompareJob(jobId, Status.PENDING, baseline, candidate,
                "En attente", null, null, null, Instant.now(), null);
    }

    public QualityCompareJob running(String step) {
        return new QualityCompareJob(jobId, Status.RUNNING, baseline, candidate, step,
                baselineReport, candidateReport, error, createdAt, completedAt);
    }

    /** Rapport de référence obtenu ; reste RUNNING (le candidat n'est pas encore évalué). */
    public QualityCompareJob withBaselineReport(QualityBenchmarkReport report, String step) {
        return new QualityCompareJob(jobId, Status.RUNNING, baseline, candidate, step,
                report, candidateReport, error, createdAt, completedAt);
    }

    public QualityCompareJob completed(QualityBenchmarkReport candidateReport) {
        return new QualityCompareJob(jobId, Status.COMPLETED, baseline, candidate, "Terminé",
                baselineReport, candidateReport, null, createdAt, Instant.now());
    }

    public QualityCompareJob failed(String error) {
        return new QualityCompareJob(jobId, Status.FAILED, baseline, candidate, "Échoué",
                baselineReport, candidateReport, error, createdAt, Instant.now());
    }
}
