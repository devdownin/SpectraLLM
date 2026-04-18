package fr.spectra.persistence;

import fr.spectra.service.dataset.DatasetGeneratorService.GenerationTask;
import jakarta.persistence.*;

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

    protected GenerationTaskEntity() {}

    public GenerationTaskEntity(String taskId, String status,
                                int pairsGenerated, int chunksProcessed,
                                int totalChunks, String error) {
        this.taskId = taskId;
        this.status = status;
        this.pairsGenerated = pairsGenerated;
        this.chunksProcessed = chunksProcessed;
        this.totalChunks = totalChunks;
        this.error = error;
    }

    public static GenerationTaskEntity fromDto(GenerationTask dto) {
        return new GenerationTaskEntity(
                dto.taskId(), dto.status().name(),
                dto.pairsGenerated(), dto.chunksProcessed(),
                dto.totalChunks(), dto.error());
    }

    public GenerationTask toDto() {
        return new GenerationTask(
                taskId,
                GenerationTask.Status.valueOf(status),
                pairsGenerated, chunksProcessed, totalChunks, error);
    }

    public String getTaskId() { return taskId; }
    public String getStatus() { return status; }
}
