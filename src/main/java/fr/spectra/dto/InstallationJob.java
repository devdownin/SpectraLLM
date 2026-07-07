package fr.spectra.dto;

import java.time.Instant;

/**
 * Suivi persistant d'une installation de modèle via le Model Hub (llmfit download).
 *
 * <p>Miroir de {@link FineTuningJob} pour le Model Hub : sans persistance, un redémarrage
 * de l'API tuait le sous-processus {@code llmfit} et effaçait tout le suivi (les sinks SSE
 * sont en mémoire). Persister le job en base donne un historique honnête — et permet à la
 * réconciliation au démarrage de marquer FAILED les téléchargements interrompus plutôt que
 * de les laisser en suspens.</p>
 *
 * <p>{@code previousActiveModel} : lorsque l'installation active automatiquement le modèle,
 * on mémorise le modèle actif <b>précédent</b>. Cela alimente la boucle « comparatif → qualité
 * mesurée » : proposer directement de comparer le nouveau modèle à celui qu'il remplace sur
 * votre corpus (benchmark qualité), et pas seulement sur le score de compatibilité matérielle.</p>
 */
public record InstallationJob(
        String jobId,
        Status status,
        String modelName,
        String quant,
        boolean autoActivate,
        int progress,
        String currentStep,
        String outputPath,
        String previousActiveModel,
        String error,
        Instant createdAt,
        Instant completedAt
) {
    public enum Status {
        PENDING, DOWNLOADING, REGISTERING, COMPLETED, FAILED
    }

    public static InstallationJob pending(String jobId, String modelName, String quant, boolean autoActivate) {
        return new InstallationJob(
                jobId, Status.PENDING, modelName, quant, autoActivate,
                0, "En attente", null, null, null, Instant.now(), null);
    }

    public InstallationJob withStatus(Status status, String step) {
        return new InstallationJob(
                jobId, status, modelName, quant, autoActivate,
                progress, step, outputPath, previousActiveModel, error, createdAt, completedAt);
    }

    /** Progression du téléchargement (0–100) ; force le statut DOWNLOADING. */
    public InstallationJob withProgress(int progress) {
        return new InstallationJob(
                jobId, Status.DOWNLOADING, modelName, quant, autoActivate,
                progress, "Téléchargement", outputPath, previousActiveModel, error, createdAt, completedAt);
    }

    /** Mémorise le modèle actif remplacé par l'activation automatique (baseline du benchmark). */
    public InstallationJob withPreviousActiveModel(String previousActiveModel) {
        return new InstallationJob(
                jobId, status, modelName, quant, autoActivate,
                progress, currentStep, outputPath, previousActiveModel, error, createdAt, completedAt);
    }

    public InstallationJob completed(String outputPath, String step) {
        return new InstallationJob(
                jobId, Status.COMPLETED, modelName, quant, autoActivate,
                100, step, outputPath, previousActiveModel, null, createdAt, Instant.now());
    }

    public InstallationJob failed(String error) {
        return new InstallationJob(
                jobId, Status.FAILED, modelName, quant, autoActivate,
                progress, "Échoué", outputPath, previousActiveModel, error, createdAt, Instant.now());
    }
}
