package fr.spectra.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Suivi d'une tâche d'ingestion asynchrone.
 *
 * <p>{@code chunksCreated} progresse en direct (chaque lot d'embeddings indexé) ;
 * {@code chunksExpected} est le dénominateur découvert au fil de l'eau : dès qu'un fichier
 * (ou une entrée ZIP) est découpé en chunks, son total s'ajoute. L'UI peut ainsi afficher
 * une vraie barre de progression déterminée pendant la phase d'embedding — la plus longue —
 * au lieu d'un simple balayage indéterminé ({@code 0} = total encore inconnu).</p>
 *
 * <p>{@code fileErrors} liste les échecs <b>par fichier</b> ({@code "nom: cause"}) : un fichier
 * en erreur n'interrompt pas la tâche, mais son échec ne doit plus être silencieux — une tâche
 * peut donc finir {@code COMPLETED} avec des {@code fileErrors} non vides (succès partiel).</p>
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
        int layoutAwareChunks,
        List<String> fileErrors
) {
    public enum Status {
        PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED
    }

    public static IngestionTask pending(String taskId, List<String> files) {
        return new IngestionTask(taskId, Status.PENDING, files, 0, 0, null, Instant.now(), null, null, 0, List.of());
    }

    public IngestionTask processing() {
        return new IngestionTask(taskId, Status.PROCESSING, files, chunksCreated, chunksExpected,
                null, createdAt, null, null, 0, fileErrors);
    }

    /** Mise à jour incrémentale du nombre de chunks pendant le traitement (statut PROCESSING). */
    public IngestionTask progress(int chunks) {
        return new IngestionTask(taskId, Status.PROCESSING, files, chunks, chunksExpected,
                null, createdAt, null, parserUsed, layoutAwareChunks, fileErrors);
    }

    /** Mise à jour du total de chunks attendus (découvert après chunking de chaque fichier). */
    public IngestionTask expecting(int expected) {
        return new IngestionTask(taskId, status, files, chunksCreated, expected,
                error, createdAt, completedAt, parserUsed, layoutAwareChunks, fileErrors);
    }

    public IngestionTask completed(int chunks) {
        return new IngestionTask(taskId, Status.COMPLETED, files, chunks, chunksExpected,
                null, createdAt, Instant.now(), null, 0, fileErrors);
    }

    public IngestionTask completed(int chunks, String parserUsed, int layoutAwareChunks) {
        return new IngestionTask(taskId, Status.COMPLETED, files, chunks, chunksExpected,
                null, createdAt, Instant.now(), parserUsed, layoutAwareChunks, fileErrors);
    }

    public IngestionTask failed(String error) {
        return new IngestionTask(taskId, Status.FAILED, files, chunksCreated, chunksExpected,
                error, createdAt, Instant.now(), null, 0, fileErrors);
    }

    public IngestionTask cancelled() {
        return new IngestionTask(taskId, Status.CANCELLED, files, chunksCreated, chunksExpected,
                "Annulé par l'utilisateur", createdAt, Instant.now(), null, 0, fileErrors);
    }

    public IngestionTask withChunks(int chunks) {
        return new IngestionTask(taskId, status, files, chunks, chunksExpected,
                error, createdAt, completedAt, parserUsed, layoutAwareChunks, fileErrors);
    }

    /** Ajoute un échec de fichier ({@code "nom: cause"}) sans changer le statut de la tâche. */
    public IngestionTask withFileError(String fileError) {
        List<String> updated = new ArrayList<>(fileErrors != null ? fileErrors : List.of());
        updated.add(fileError);
        return new IngestionTask(taskId, status, files, chunksCreated, chunksExpected,
                error, createdAt, completedAt, parserUsed, layoutAwareChunks, List.copyOf(updated));
    }

    /** Remplace la liste des échecs de fichiers (préparation en amont de l'exécuteur). */
    public IngestionTask withFileErrors(List<String> errors) {
        return new IngestionTask(taskId, status, files, chunksCreated, chunksExpected,
                error, createdAt, completedAt, parserUsed, layoutAwareChunks,
                errors != null ? List.copyOf(errors) : List.of());
    }
}
