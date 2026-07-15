package fr.spectra.dto;

import java.time.Instant;

/**
 * Suivi d'un passage d'ablation A/B asynchrone.
 *
 * <p>Le passage d'ablation ({@code RagAblationService.run}) est très lent sur CPU (plusieurs
 * appels LLM par question × bras × répétitions) : le lancer en synchrone tenait une requête
 * HTTP ouverte jusqu'à 30 minutes, sans progression, sans annulation, et le rapport était
 * perdu au moindre rechargement de la page. On le pilote donc comme un job asynchrone suivi,
 * à l'image de {@link QualityCompareJob} : statut, progression réelle, annulation coopérative
 * et rapport persisté côté serveur.</p>
 *
 * <p>{@code totalUnits}/{@code processedUnits} comptent les unités de travail réelles
 * (une unité = une question générée ou notée : {@code bras × runs × questions × 2}), pour une
 * barre de progression honnête.</p>
 */
public record AblationJob(
        String jobId,
        Status status,
        /** Libellé lisible du passage (ex. « 4 bras × 1 run · 25 questions »). */
        String label,
        int totalUnits,
        int processedUnits,
        String currentStep,
        /** Rapport final — présent uniquement en COMPLETED. */
        AblationReport report,
        String error,
        Instant createdAt,
        Instant completedAt
) {
    public enum Status {
        PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
    }

    public static AblationJob pending(String jobId, String label, int totalUnits) {
        return new AblationJob(jobId, Status.PENDING, label, totalUnits, 0,
                "En attente", null, null, Instant.now(), null);
    }

    public AblationJob progress(int processedUnits, String step) {
        return new AblationJob(jobId, Status.RUNNING, label, totalUnits, processedUnits,
                step, report, error, createdAt, completedAt);
    }

    public AblationJob completed(AblationReport report) {
        return new AblationJob(jobId, Status.COMPLETED, label, totalUnits, totalUnits,
                "Terminé", report, null, createdAt, Instant.now());
    }

    public AblationJob failed(String error) {
        return new AblationJob(jobId, Status.FAILED, label, totalUnits, processedUnits,
                "Échoué", report, error, createdAt, Instant.now());
    }

    public AblationJob cancelled() {
        return new AblationJob(jobId, Status.CANCELLED, label, totalUnits, processedUnits,
                "Annulé", report, null, createdAt, Instant.now());
    }
}
