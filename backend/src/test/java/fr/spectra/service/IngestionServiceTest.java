package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.IngestionTask;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.service.extraction.DocumentExtractorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionServiceTest {

    @TempDir
    Path tempDir;

    private IngestionService ingestionService;
    private IngestionTaskExecutor executor;
    private SpectraProperties props;

    @BeforeEach
    void setUp() {
        SpectraProperties.PipelineProperties pipeline = new SpectraProperties.PipelineProperties(
                512, 64, 10, 30, 120, 4);
        props = mock(SpectraProperties.class);
        when(props.pipeline()).thenReturn(pipeline);

        executor = mock(IngestionTaskExecutor.class);
        ingestionService = newService(0); // 0 = pas de plafond de contre-pression
    }

    /** Construit un service avec un plafond de tâches actives donné (0 = illimité). */
    private IngestionService newService(int maxActiveIngestions) {
        ChromaDbClient chromaDb = mock(ChromaDbClient.class);
        when(chromaDb.getOrCreateCollection(anyString())).thenReturn("test-collection-id");
        return new IngestionService(
                mock(DocumentExtractorFactory.class),
                mock(TextCleanerService.class),
                mock(ChunkingService.class),
                mock(EmbeddingService.class),
                chromaDb,
                mock(FtsService.class),
                executor,
                mock(IngestedFileRepository.class),
                mock(GedService.class),
                mock(fr.spectra.persistence.StreamSourceRepository.class),
                props,
                50,
                4,
                maxActiveIngestions
        );
    }

    @Test
    void submit_createsTaskWithPendingStatus() {
        byte[] content = "contenu unique du fichier".getBytes();
        MockMultipartFile file = new MockMultipartFile("files", "doc.txt", "text/plain", content);

        String taskId = ingestionService.submit(List.of(file));

        assertThat(taskId).isNotBlank();
        IngestionTask task = ingestionService.getTask(taskId);
        assertThat(task).isNotNull();
        assertThat(task.taskId()).isEqualTo(taskId);
    }

    @Test
    void submit_sameContentWhileInFlight_secondIsSkipped() {
        byte[] content = "contenu identique pour deux uploads simultanes".getBytes();
        // Le premier upload réserve le hachage ; l'exécuteur étant mocké, il n'appelle pas le
        // callback qui libère la réservation → le second upload du même contenu est ignoré.
        String first = ingestionService.submit(List.of(
                new MockMultipartFile("files", "a.txt", "text/plain", content)));
        String second = ingestionService.submit(List.of(
                new MockMultipartFile("files", "b.txt", "text/plain", content)));

        // Le second n'a rien à traiter : tâche terminée immédiatement, exécuteur non ré-appelé.
        assertThat(ingestionService.getTask(second).status())
                .isEqualTo(IngestionTask.Status.COMPLETED);
        assertThat(ingestionService.getTask(second).chunksCreated()).isZero();
        assertThat(first).isNotEqualTo(second);
        verify(executor, times(1)).execute(anyString(), any(), any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void getTask_returnsNullForUnknownId() {
        assertThat(ingestionService.getTask("does-not-exist")).isNull();
    }

    @Test
    void registerTask_createsAndStoresTask() {
        IngestionTask task = ingestionService.registerTask("task-123", List.of("file.txt"));

        assertThat(task).isNotNull();
        assertThat(task.taskId()).isEqualTo("task-123");
        assertThat(ingestionService.getTask("task-123")).isEqualTo(task);
    }

    @Test
    void updateTask_updatesStoredTask() {
        ingestionService.registerTask("task-456", List.of("a.txt"));
        IngestionTask updated = ingestionService.getTask("task-456").processing();
        ingestionService.updateTask("task-456", updated);

        assertThat(ingestionService.getTask("task-456").status())
                .isEqualTo(IngestionTask.Status.PROCESSING);
    }

    // ── Contre-pression : plafond de tâches actives ───────────────────────────

    @Test
    void submit_atActiveCapacity_rejectsWith429() {
        IngestionService capped = newService(1);
        // 1re soumission : l'exécuteur est mocké (no-op) → la tâche reste PENDING, plafond atteint.
        capped.submit(List.of(new MockMultipartFile("files", "a.txt", "text/plain",
                "premier document distinct".getBytes())));
        assertThat(capped.activeIngestionCount()).isEqualTo(1);

        // 2e soumission : refusée AVANT toute écriture temporaire.
        assertThatThrownBy(() -> capped.submit(List.of(
                new MockMultipartFile("files", "b.txt", "text/plain",
                        "second document distinct".getBytes()))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(t -> assertThat(((ResponseStatusException) t).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        // La tâche refusée n'a pas été enregistrée.
        assertThat(capped.activeIngestionCount()).isEqualTo(1);
    }

    @Test
    void submit_unlimitedByDefault_neverRejects() {
        // maxActive=0 (défaut) : plusieurs soumissions distinctes sont toutes acceptées.
        for (int i = 0; i < 5; i++) {
            ingestionService.submit(List.of(new MockMultipartFile("files", "f" + i + ".txt",
                    "text/plain", ("document distinct numero " + i).getBytes())));
        }
        assertThat(ingestionService.activeIngestionCount()).isEqualTo(5);
    }
}
