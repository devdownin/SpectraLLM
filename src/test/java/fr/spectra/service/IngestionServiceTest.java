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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IngestionServiceTest {

    @TempDir
    Path tempDir;

    private IngestionService ingestionService;

    @BeforeEach
    void setUp() {
        SpectraProperties.PipelineProperties pipeline = new SpectraProperties.PipelineProperties(
                512, 64, 10, 30, 120, 4);
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.pipeline()).thenReturn(pipeline);

        ChromaDbClient chromaDb = mock(ChromaDbClient.class);
        when(chromaDb.getOrCreateCollection(anyString())).thenReturn("test-collection-id");

        ingestionService = new IngestionService(
                mock(DocumentExtractorFactory.class),
                mock(TextCleanerService.class),
                mock(ChunkingService.class),
                mock(EmbeddingService.class),
                chromaDb,
                mock(IngestionTaskExecutor.class),
                mock(IngestedFileRepository.class),
                mock(GedService.class),
                props
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
}
