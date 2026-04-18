package fr.spectra.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.dto.FineTuningJob;
import fr.spectra.dto.FineTuningRequest;
import jakarta.persistence.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

@Entity
@Table(name = "fine_tuning_jobs")
public class FineTuningJobEntity {

    private static final Logger log = LoggerFactory.getLogger(FineTuningJobEntity.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    private String jobId;

    private String status;
    private String modelName;
    private String baseModel;

    @Column(columnDefinition = "TEXT")
    private String parameters; // JSON sérialisé de FineTuningRequest

    private int datasetSize;
    private String currentStep;
    private Integer currentEpoch;
    private Integer totalEpochs;
    private Double loss;
    private String outputPath;
    private String reportPath;

    @Column(columnDefinition = "TEXT")
    private String error;

    private Instant createdAt;
    private Instant completedAt;

    protected FineTuningJobEntity() {}

    public FineTuningJobEntity(String jobId, String status, String modelName, String baseModel,
                               String parameters, int datasetSize, String currentStep,
                               Integer currentEpoch, Integer totalEpochs, Double loss,
                               String outputPath, String reportPath, String error,
                               Instant createdAt, Instant completedAt) {
        this.jobId = jobId;
        this.status = status;
        this.modelName = modelName;
        this.baseModel = baseModel;
        this.parameters = parameters;
        this.datasetSize = datasetSize;
        this.currentStep = currentStep;
        this.currentEpoch = currentEpoch;
        this.totalEpochs = totalEpochs;
        this.loss = loss;
        this.outputPath = outputPath;
        this.reportPath = reportPath;
        this.error = error;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }

    public static FineTuningJobEntity fromDto(FineTuningJob dto) {
        String paramsJson;
        try {
            paramsJson = MAPPER.writeValueAsString(dto.parameters());
        } catch (Exception e) {
            paramsJson = "{}";
        }
        return new FineTuningJobEntity(
                dto.jobId(), dto.status().name(), dto.modelName(), dto.baseModel(),
                paramsJson, dto.datasetSize(), dto.currentStep(),
                dto.currentEpoch(), dto.totalEpochs(), dto.loss(),
                dto.outputPath(), dto.reportPath(), dto.error(),
                dto.createdAt(), dto.completedAt());
    }

    public FineTuningJob toDto() {
        FineTuningRequest req = null;
        try {
            if (parameters != null && !parameters.isBlank()) {
                req = MAPPER.readValue(parameters, FineTuningRequest.class);
            }
        } catch (Exception e) {
            log.warn("Paramètres JSON corrompus pour le job '{}', champ ignoré: {}", jobId, e.getMessage());
        }

        return new FineTuningJob(
                jobId, FineTuningJob.Status.valueOf(status),
                modelName, baseModel, req, datasetSize,
                currentStep, currentEpoch, totalEpochs, loss,
                outputPath, reportPath, error, createdAt, completedAt);
    }

    public String getJobId() { return jobId; }
    public String getStatus() { return status; }
}
