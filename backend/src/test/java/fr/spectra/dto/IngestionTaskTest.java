package fr.spectra.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires de IngestionTask — machine à états PENDING → PROCESSING → COMPLETED/FAILED.
 */
class IngestionTaskTest {

    // ── pending ───────────────────────────────────────────────────────────────

    @Test
    void pending_statusIsPending() {
        IngestionTask task = IngestionTask.pending("t1", List.of("a.pdf"));
        assertThat(task.status()).isEqualTo(IngestionTask.Status.PENDING);
    }

    @Test
    void pending_taskIdPreserved() {
        IngestionTask task = IngestionTask.pending("my-task-id", List.of());
        assertThat(task.taskId()).isEqualTo("my-task-id");
    }

    @Test
    void pending_filesPreserved() {
        List<String> files = List.of("a.pdf", "b.json", "c.xml");
        IngestionTask task = IngestionTask.pending("t1", files);
        assertThat(task.files()).containsExactlyElementsOf(files);
    }

    @Test
    void pending_chunksIsZero() {
        assertThat(IngestionTask.pending("t1", List.of()).chunksCreated()).isZero();
    }

    @Test
    void pending_errorIsNull() {
        assertThat(IngestionTask.pending("t1", List.of()).error()).isNull();
    }

    @Test
    void pending_createdAtNotNull() {
        assertThat(IngestionTask.pending("t1", List.of()).createdAt()).isNotNull();
    }

    @Test
    void pending_completedAtIsNull() {
        assertThat(IngestionTask.pending("t1", List.of()).completedAt()).isNull();
    }

    // ── processing ────────────────────────────────────────────────────────────

    @Test
    void processing_statusIsProcessing() {
        IngestionTask task = IngestionTask.pending("t1", List.of()).processing();
        assertThat(task.status()).isEqualTo(IngestionTask.Status.PROCESSING);
    }

    @Test
    void processing_taskIdPreserved() {
        IngestionTask task = IngestionTask.pending("t1", List.of()).processing();
        assertThat(task.taskId()).isEqualTo("t1");
    }

    @Test
    void processing_completedAtIsNull() {
        IngestionTask task = IngestionTask.pending("t1", List.of()).processing();
        assertThat(task.completedAt()).isNull();
    }

    // ── completed(int) ────────────────────────────────────────────────────────

    @Test
    void completed_statusIsCompleted() {
        IngestionTask task = IngestionTask.pending("t1", List.of()).processing().completed(42);
        assertThat(task.status()).isEqualTo(IngestionTask.Status.COMPLETED);
    }

    @Test
    void completed_chunksCreatedPreserved() {
        IngestionTask task = IngestionTask.pending("t1", List.of()).completed(17);
        assertThat(task.chunksCreated()).isEqualTo(17);
    }

    @Test
    void completed_completedAtNotNull() {
        IngestionTask task = IngestionTask.pending("t1", List.of()).completed(5);
        assertThat(task.completedAt()).isNotNull();
    }

    @Test
    void completed_errorIsNull() {
        IngestionTask task = IngestionTask.pending("t1", List.of()).completed(5);
        assertThat(task.error()).isNull();
    }

    // ── completed(int, String, int) ───────────────────────────────────────────

    @Test
    void completed_withParser_parserUsedPreserved() {
        IngestionTask task = IngestionTask.pending("t1", List.of()).completed(10, "docparser", 5);
        assertThat(task.parserUsed()).isEqualTo("docparser");
        assertThat(task.layoutAwareChunks()).isEqualTo(5);
    }

    @Test
    void completed_withNullParser_parserUsedIsNull() {
        IngestionTask task = IngestionTask.pending("t1", List.of()).completed(10, null, 0);
        assertThat(task.parserUsed()).isNull();
    }

    // ── failed ────────────────────────────────────────────────────────────────

    @Test
    void failed_statusIsFailed() {
        IngestionTask task = IngestionTask.pending("t1", List.of()).failed("Erreur réseau");
        assertThat(task.status()).isEqualTo(IngestionTask.Status.FAILED);
    }

    @Test
    void failed_errorMessagePreserved() {
        IngestionTask task = IngestionTask.pending("t1", List.of()).failed("Timeout ChromaDB");
        assertThat(task.error()).isEqualTo("Timeout ChromaDB");
    }

    @Test
    void failed_completedAtNotNull() {
        IngestionTask task = IngestionTask.pending("t1", List.of()).failed("err");
        assertThat(task.completedAt()).isNotNull();
    }

    @Test
    void failed_taskIdPreserved() {
        IngestionTask task = IngestionTask.pending("task-xyz", List.of()).failed("err");
        assertThat(task.taskId()).isEqualTo("task-xyz");
    }

    // ── Immutabilité ──────────────────────────────────────────────────────────

    @Test
    void transitions_originalUnchanged() {
        IngestionTask original = IngestionTask.pending("t1", List.of("f.pdf"));
        original.processing();
        // L'original reste PENDING
        assertThat(original.status()).isEqualTo(IngestionTask.Status.PENDING);
    }

    @Test
    void createdAt_preservedAcrossTransitions() {
        IngestionTask pending = IngestionTask.pending("t1", List.of());
        Instant created = pending.createdAt();

        IngestionTask completed = pending.processing().completed(5);
        assertThat(completed.createdAt()).isEqualTo(created);
    }
}
