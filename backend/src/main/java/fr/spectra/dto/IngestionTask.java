package fr.spectra.dto;

import java.time.Instant;
import java.util.List;

/**
 * Suivi d'une tâche d'ingestion asynchrone.
 *
 * <p>{@code chunksCreated} progresse en direct (chaque lot d'embeddings indexé) ;
 * {@code chunksExpected} est le dénominateur découvert au fil de l'eau : dès qu'un fichier
 * (ou une entrée ZIP) est découpé en chunks, son total s'ajoute. L'UI peut ainsi afficher
 * une vraie barre de progression déterminée pendant la phase d'embedding — la plus longue —
 * au lieu d'un simple balayage indéterminé ({@code 0} = total encore inconnu).</p>
 */
public record IngestionTask(
        String taskId,
        Status status,
        List<String> files,
        int chunksCreated,
        int chunksExpected,
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
        return new IngestionTask(taskId, Status.PENDING, files, 0, 0, null, Instant.now(), null, null, 0);
    }

    public IngestionTask processing() {
        return new IngestionTask(taskId, Status.PROCESSING, files, chunksCreated, chunksExpected,
                null, createdAt, null, null, 0);
    }

    /** Mise à jour incrémentale du nombre de chunks pendant le traitement (statut PROCESSING). */
    public IngestionTask progress(int chunks) {
        return new IngestionTask(taskId, Status.PROCESSING, files, chunks, chunksExpected,
                null, createdAt, null, parserUsed, layoutAwareChunks);
    }

    /** Mise à jour du total de chunks attendus (découvert après chunking de chaque fichier). */
    public IngestionTask expecting(int expected) {
        return new IngestionTask(taskId, status, files, chunksCreated, expected,
                error, createdAt, completedAt, parserUsed, layoutAwareChunks);
    }

    public IngestionTask completed(int chunks) {
        return new IngestionTask(taskId, Status.COMPLETED, files, chunks, chunksExpected,
                null, createdAt, Instant.now(), null, 0);
    }

    public IngestionTask completed(int chunks, String parserUsed, int layoutAwareChunks) {
        return new IngestionTask(taskId, Status.COMPLETED, files, chunks, chunksExpected,
                null, createdAt, Instant.now(), parserUsed, layoutAwareChunks);
    }

    public IngestionTask failed(String error) {
        return new IngestionTask(taskId, Status.FAILED, files, chunksCreated, chunksExpected,
                error, createdAt, Instant.now(), null, 0);
    }

    public IngestionTask cancelled() {
        return new IngestionTask(taskId, Status.CANCELLED, files, chunksCreated, chunksExpected,
                "Annulé par l'utilisateur", createdAt, Instant.now(), null, 0);
    }

    public IngestionTask withChunks(int chunks) {
        return new IngestionTask(taskId, status, files, chunks, chunksExpected,
                error, createdAt, completedAt, parserUsed, layoutAwareChunks);
    }
}
