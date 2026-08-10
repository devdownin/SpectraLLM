package fr.spectra.persistence;

import fr.spectra.service.dataset.DpoGenerationService.DpoTask;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * Persistance d'une tâche de génération DPO.
 *
 * <p>Calquée sur {@link GenerationTaskEntity} : même découpage, mêmes conversions
 * {@code fromDto}/{@code toDto}. La génération DPO était la dernière famille de tâches
 * asynchrones dont l'état vivait uniquement en mémoire — voir
 * {@code docs/process/audit-simplification.fr.md} (S1).
 */
@Entity
@Table(name = "dpo_tasks")
public class DpoTaskEntity {

    @Id
    private String taskId;

    private String status;
    private int processed;
    private int total;

    @Column(columnDefinition = "TEXT")
    private String error;

    private Instant startedAt;
    private Instant completedAt;

    protected DpoTaskEntity() {}

    public DpoTaskEntity(String taskId, String status, int processed, int total,
                         String error, Instant startedAt, Instant completedAt) {
        this.taskId = taskId;
        this.status = status;
        this.processed = processed;
        this.total = total;
        this.error = error;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public static DpoTaskEntity fromDto(DpoTask dto) {
        return new DpoTaskEntity(
                dto.taskId(), dto.status(), dto.processed(), dto.total(), dto.error(),
                dto.startedAt() != null ? dto.startedAt() : Instant.now(),
                dto.completedAt());
    }

    public DpoTask toDto() {
        return new DpoTask(taskId, status, processed, total, error, startedAt, completedAt);
    }

    public String getTaskId() { return taskId; }
    public String getStatus() { return status; }
}
