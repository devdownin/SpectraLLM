package fr.spectra.dto;

import java.time.Instant;
import java.util.List;

public record IngestionTask(
        String taskId,
        Status status,
        List<String> files,
        int chunksCreated,
        String error,
        Instant createdAt,
        Instant completedAt,
        String parserUsed,
        int layoutAwareChunks
) {
    public enum Status {
        PENDING, PROCESSING, COMPLETED, FAILED
    }

    public static IngestionTask pending(String taskId, List<String> files) {
        return new IngestionTask(taskId, Status.PENDING, files, 0, null, Instant.now(), null, null, 0);
    }

    public IngestionTask processing() {
        return new IngestionTask(taskId, Status.PROCESSING, files, chunksCreated, null, createdAt, null, null, 0);
    }

    public IngestionTask completed(int chunks) {
        return new IngestionTask(taskId, Status.COMPLETED, files, chunks, null, createdAt, Instant.now(), null, 0);
    }

    public IngestionTask completed(int chunks, String parserUsed, int layoutAwareChunks) {
        return new IngestionTask(taskId, Status.COMPLETED, files, chunks, null, createdAt, Instant.now(),
                parserUsed, layoutAwareChunks);
    }

    public IngestionTask failed(String error) {
        return new IngestionTask(taskId, Status.FAILED, files, chunksCreated, error, createdAt, Instant.now(), null, 0);
    }
}
