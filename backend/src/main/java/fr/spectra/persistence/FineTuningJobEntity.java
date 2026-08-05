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
    /** Fractionnaire : voir {@link FineTuningJob#currentEpoch()} (colonne migrée en DOUBLE). */
    private Double currentEpoch;
    private Integer totalEpochs;
    private Double loss;
    private Double evalLoss;
    private String outputPath;
    private String reportPath;

    @Column(columnDefinition = "TEXT")
    private String error;

    /** Phase atteinte à l'échec — voir {@link FineTuningJob#failedPhase()}. Stockée en clair. */
    @Column(length = 50)
    private String failedPhase;

    /** Évaluation enchaînée après enregistrement — voir {@link FineTuningJob#evaluationId()}. */
    @Column(length = 64)
    private String evaluationId;

    private Instant createdAt;
    private Instant completedAt;

    protected FineTuningJobEntity() {}

    public FineTuningJobEntity(String jobId, String status, String modelName, String baseModel,
                               String parameters, int datasetSize, String currentStep,
                               Double currentEpoch, Integer totalEpochs, Double loss,
                               Double evalLoss, String outputPath, String reportPath, String error,
                               String failedPhase, String evaluationId,
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
        this.evalLoss = evalLoss;
        this.outputPath = outputPath;
        this.reportPath = reportPath;
        this.error = error;
        this.failedPhase = failedPhase;
        this.evaluationId = evaluationId;
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
                dto.currentEpoch(), dto.totalEpochs(), dto.loss(), dto.evalLoss(),
                dto.outputPath(), dto.reportPath(), dto.error(),
                dto.failedPhase() != null ? dto.failedPhase().name() : null,
                dto.evaluationId(), dto.createdAt(), dto.completedAt());
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
                currentStep, currentEpoch, totalEpochs, loss, evalLoss,
                outputPath, reportPath, error, parseFailedPhase(), evaluationId, createdAt, completedAt);
    }

    /**
     * Phase d'échec relue depuis la base. Tolérante : une valeur inconnue (colonne écrite par une
     * version ultérieure, ou éditée à la main) rend {@code null} plutôt que de faire échouer la
     * lecture de TOUT l'historique des jobs sur un {@code IllegalArgumentException}.
     */
    private FineTuningJob.Status parseFailedPhase() {
        if (failedPhase == null || failedPhase.isBlank()) return null;
        try {
            return FineTuningJob.Status.valueOf(failedPhase);
        } catch (IllegalArgumentException e) {
            log.warn("Phase d'échec inconnue '{}' pour le job '{}', champ ignoré", failedPhase, jobId);
            return null;
        }
    }

    public String getJobId() { return jobId; }
    public String getStatus() { return status; }
}
