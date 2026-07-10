package fr.spectra.persistence;

import fr.spectra.service.dataset.DatasetGeneratorService.GenerationTask;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "generation_tasks")
public class GenerationTaskEntity {

    @Id
    private String taskId;

    private String status;
    private int pairsGenerated;
    private int chunksProcessed;
    private int totalChunks;

    @Column(columnDefinition = "TEXT")
    private String error;

    private Instant createdAt;

    protected GenerationTaskEntity() {}

    public GenerationTaskEntity(String taskId, String status,
                                int pairsGenerated, int chunksProcessed,
                                int totalChunks, String error, Instant createdAt) {
        this.taskId = taskId;
        this.status = status;
        this.pairsGenerated = pairsGenerated;
        this.chunksProcessed = chunksProcessed;
        this.totalChunks = totalChunks;
        this.error = error;
        this.createdAt = createdAt;
    }

    public static GenerationTaskEntity fromDto(GenerationTask dto) {
        return new GenerationTaskEntity(
                dto.taskId(), dto.status().name(),
                dto.pairsGenerated(), dto.chunksProcessed(),
                dto.totalChunks(), dto.error(),
                dto.createdAt() != null ? dto.createdAt() : Instant.now());
    }

    public GenerationTask toDto() {
        return new GenerationTask(
                taskId,
                GenerationTask.Status.valueOf(status),
                pairsGenerated, chunksProcessed, totalChunks, error,
                createdAt);
    }

    public String getTaskId() { return taskId; }
    public String getStatus() { return status; }
}
