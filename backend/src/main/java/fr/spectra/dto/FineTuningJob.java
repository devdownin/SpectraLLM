package fr.spectra.dto;

import java.time.Instant;

public record FineTuningJob(
        String jobId,
        Status status,
        String modelName,
        String baseModel,
        FineTuningRequest parameters,
        int datasetSize,
        String currentStep,
        Integer currentEpoch,
        Integer totalEpochs,
        Double loss,
        /**
         * Loss mesurée sur le split de validation à la fin de chaque époque ({@code null} si
         * {@code valSplit = 0}, ou en DPO/ORPO). C'est la seule des deux courbes qui signale un
         * sur-apprentissage : la loss d'entraînement décroît par construction.
         */
        Double evalLoss,
        String outputPath,
        String reportPath,
        String error,
        Instant createdAt,
        Instant completedAt
) {
    public enum Status {
        PENDING, EXPORTING_DATASET, TRAINING, IMPORTING_MODEL, COMPLETED, FAILED
    }

    public static FineTuningJob pending(String jobId, FineTuningRequest request) {
        return new FineTuningJob(
                jobId, Status.PENDING, request.modelName(), request.baseModel(),
                request, 0, "En attente", null, request.epochs(),
                null, null, null, null, null, Instant.now(), null
        );
    }

    public FineTuningJob withStatus(Status status, String step) {
        return new FineTuningJob(
                jobId, status, modelName, baseModel, parameters, datasetSize,
                step, currentEpoch, totalEpochs, loss, evalLoss, outputPath, reportPath,
                error, createdAt, completedAt
        );
    }

    /**
     * Progression d'entraînement. Une ligne de sortie ne porte jamais les deux losses à la fois
     * (le trainer logue la training loss par étape et l'eval_loss en fin d'époque) : un argument
     * {@code null} signifie « pas de nouvelle valeur », et la précédente est conservée plutôt
     * qu'effacée.
     */
    public FineTuningJob withTrainingProgress(int epoch, Double loss, Double evalLoss) {
        return new FineTuningJob(
                jobId, Status.TRAINING, modelName, baseModel, parameters, datasetSize,
                "Entraînement epoch " + epoch + "/" + totalEpochs, epoch, totalEpochs,
                loss != null ? loss : this.loss,
                evalLoss != null ? evalLoss : this.evalLoss,
                outputPath, reportPath, error, createdAt, completedAt
        );
    }

    public FineTuningJob withDatasetSize(int size) {
        return new FineTuningJob(
                jobId, status, modelName, baseModel, parameters, size,
                currentStep, currentEpoch, totalEpochs, loss, evalLoss, outputPath, reportPath,
                error, createdAt, completedAt
        );
    }

    public FineTuningJob completed(String outputPath) {
        return new FineTuningJob(
                jobId, Status.COMPLETED, modelName, baseModel, parameters, datasetSize,
                "Terminé", totalEpochs, totalEpochs, loss, evalLoss, outputPath, reportPath, null,
                createdAt, Instant.now()
        );
    }

    public FineTuningJob failed(String error) {
        return new FineTuningJob(
                jobId, Status.FAILED, modelName, baseModel, parameters, datasetSize,
                "Échoué", currentEpoch, totalEpochs, loss, evalLoss, outputPath, reportPath, error,
                createdAt, Instant.now()
        );
    }
}
