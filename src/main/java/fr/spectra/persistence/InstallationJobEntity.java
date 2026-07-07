package fr.spectra.persistence;

import fr.spectra.dto.InstallationJob;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Entité JPA du suivi d'installation Model Hub. Même stratégie que
 * {@link FineTuningJobEntity} : le job survit au redémarrage de la JVM, ce qui rend
 * l'historique fiable et rend possible la réconciliation des téléchargements interrompus.
 */
@Entity
@Table(name = "installation_jobs")
public class InstallationJobEntity {

    @Id
    private String jobId;

    private String status;
    private String modelName;
    private String quant;
    private boolean autoActivate;
    private int progress;
    private String currentStep;
    private String outputPath;

    @Column(columnDefinition = "TEXT")
    private String error;

    private Instant createdAt;
    private Instant completedAt;

    protected InstallationJobEntity() {}

    public InstallationJobEntity(String jobId, String status, String modelName, String quant,
                                 boolean autoActivate, int progress, String currentStep,
                                 String outputPath, String error,
                                 Instant createdAt, Instant completedAt) {
        this.jobId = jobId;
        this.status = status;
        this.modelName = modelName;
        this.quant = quant;
        this.autoActivate = autoActivate;
        this.progress = progress;
        this.currentStep = currentStep;
        this.outputPath = outputPath;
        this.error = error;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }

    public static InstallationJobEntity fromDto(InstallationJob dto) {
        return new InstallationJobEntity(
                dto.jobId(), dto.status().name(), dto.modelName(), dto.quant(),
                dto.autoActivate(), dto.progress(), dto.currentStep(),
                dto.outputPath(), dto.error(), dto.createdAt(), dto.completedAt());
    }

    public InstallationJob toDto() {
        return new InstallationJob(
                jobId, InstallationJob.Status.valueOf(status), modelName, quant,
                autoActivate, progress, currentStep, outputPath, error,
                createdAt, completedAt);
    }

    public String getJobId() { return jobId; }
    public String getStatus() { return status; }
}
