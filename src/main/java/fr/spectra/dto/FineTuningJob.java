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
                null, null, null, null, Instant.now(), null
        );
    }

    public FineTuningJob withStatus(Status status, String step) {
        return new FineTuningJob(
                jobId, status, modelName, baseModel, parameters, datasetSize,
                step, currentEpoch, totalEpochs, loss, outputPath, reportPath, error, createdAt, completedAt
        );
    }

    public FineTuningJob withTrainingProgress(int epoch, Double loss) {
        return new FineTuningJob(
                jobId, Status.TRAINING, modelName, baseModel, parameters, datasetSize,
                "Entraînement epoch " + epoch + "/" + totalEpochs, epoch, totalEpochs,
                loss, outputPath, reportPath, error, createdAt, completedAt
        );
    }

    public FineTuningJob withDatasetSize(int size) {
        return new FineTuningJob(
                jobId, status, modelName, baseModel, parameters, size,
                currentStep, currentEpoch, totalEpochs, loss, outputPath, reportPath, error, createdAt, completedAt
        );
    }

    public FineTuningJob completed(String outputPath) {
        return new FineTuningJob(
                jobId, Status.COMPLETED, modelName, baseModel, parameters, datasetSize,
                "Terminé", totalEpochs, totalEpochs, loss, outputPath, reportPath, null,
                createdAt, Instant.now()
        );
    }

    public FineTuningJob failed(String error) {
        return new FineTuningJob(
                jobId, Status.FAILED, modelName, baseModel, parameters, datasetSize,
                "Échoué", currentEpoch, totalEpochs, loss, outputPath, reportPath, error,
                createdAt, Instant.now()
        );
    }
}
