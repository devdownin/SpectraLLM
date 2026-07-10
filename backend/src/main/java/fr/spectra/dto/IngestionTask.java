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
        PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED
    }

    public static IngestionTask pending(String taskId, List<String> files) {
        return new IngestionTask(taskId, Status.PENDING, files, 0, null, Instant.now(), null, null, 0);
    }

    public IngestionTask processing() {
        return new IngestionTask(taskId, Status.PROCESSING, files, chunksCreated, null, createdAt, null, null, 0);
    }

    /** Mise à jour incrémentale du nombre de chunks pendant le traitement (statut PROCESSING). */
    public IngestionTask progress(int chunks) {
        return new IngestionTask(taskId, Status.PROCESSING, files, chunks, null, createdAt, null,
                parserUsed, layoutAwareChunks);
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

    public IngestionTask cancelled() {
        return new IngestionTask(taskId, Status.CANCELLED, files, chunksCreated, "Annulé par l'utilisateur",
                createdAt, Instant.now(), null, 0);
    }

    public IngestionTask withChunks(int chunks) {
        return new IngestionTask(taskId, status, files, chunks, error, createdAt, completedAt, parserUsed, layoutAwareChunks);
    }
}
