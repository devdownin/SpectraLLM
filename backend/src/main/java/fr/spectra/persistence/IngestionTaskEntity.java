package fr.spectra.persistence;

import fr.spectra.dto.IngestionTask;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "ingestion_tasks")
public class IngestionTaskEntity {

    @Id
    private String taskId;

    private String status;

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> files;

    private int chunksCreated;

    private int chunksExpected;

    @Column(columnDefinition = "TEXT")
    private String error;

    private Instant createdAt;
    private Instant completedAt;

    private String parserUsed;
    private int layoutAwareChunks;

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> fileErrors;

    protected IngestionTaskEntity() {}

    public IngestionTaskEntity(String taskId, String status, List<String> files,
                               int chunksCreated, int chunksExpected, String error,
                               Instant createdAt, Instant completedAt,
                               String parserUsed, int layoutAwareChunks,
                               List<String> fileErrors) {
        this.taskId = taskId;
        this.status = status;
        this.files = files;
        this.chunksCreated = chunksCreated;
        this.chunksExpected = chunksExpected;
        this.error = error;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
        this.parserUsed = parserUsed;
        this.layoutAwareChunks = layoutAwareChunks;
        this.fileErrors = fileErrors;
    }

    public static IngestionTaskEntity fromDto(IngestionTask dto) {
        return new IngestionTaskEntity(
                dto.taskId(), dto.status().name(), dto.files(),
                dto.chunksCreated(), dto.chunksExpected(), dto.error(),
                dto.createdAt(), dto.completedAt(),
                dto.parserUsed(), dto.layoutAwareChunks(), dto.fileErrors());
    }

    public IngestionTask toDto() {
        return new IngestionTask(
                taskId,
                IngestionTask.Status.valueOf(status),
                files,
                chunksCreated,
                chunksExpected,
                error,
                createdAt,
                completedAt,
                parserUsed,
                layoutAwareChunks,
                fileErrors != null ? fileErrors : List.of());
    }

    public String getTaskId() { return taskId; }
}
