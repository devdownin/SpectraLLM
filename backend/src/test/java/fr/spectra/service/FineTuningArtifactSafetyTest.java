package fr.spectra.service;

import fr.spectra.persistence.FineTuningJobEntity;
import fr.spectra.persistence.FineTuningJobRepository;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.service.dataset.DatasetGeneratorService;
import fr.spectra.service.dataset.DpoGenerationService;
import fr.spectra.service.training.TrainingRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FineTuningArtifactSafetyTest {
    @TempDir Path workDir;

    @Test
    void publishesPerJobWithoutReplacingPreviousModels() throws Exception {
        Path source = workDir.resolve("source.gguf");
        Files.writeString(source, "first model");
        Path models = workDir.resolve("models");
        Path first = FineTuningService.publishGguf(source, models, "job-a");
        Files.writeString(source, "second model");
        Path second = FineTuningService.publishGguf(source, models, "job-b");
        assertThat(first).isNotEqualTo(second);
        assertThat(first.getParent()).isEqualTo(models);
        assertThat(first.getFileName().toString()).isEqualTo("fine-tuning-job-a.gguf");
        assertThat(Files.readString(first)).isEqualTo("first model");
        assertThat(Files.readString(second)).isEqualTo("second model");
        assertThatThrownBy(() -> FineTuningService.publishGguf(source, models, "job-a"))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readString(first)).isEqualTo("first model");
        assertThat(Files.exists(models.resolve(".fine-tuning-job-a/model.gguf.part"))).isFalse();
    }

    @Test
    void refusesAnExistingTargetEvenWithoutAnExistingPublicationClaim() throws Exception {
        Path models = workDir.resolve("models");
        Files.createDirectories(models);
        Path target = models.resolve("fine-tuning-job-a.gguf");
        Files.writeString(target, "existing model");
        Path source = workDir.resolve("source.gguf");
        Files.writeString(source, "new model");
        assertThatThrownBy(() -> FineTuningService.publishGguf(source, models, "job-a"))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readString(target)).isEqualTo("existing model");
    }

    @Test
    void concurrentPublicationOfTheSameJobHasExactlyOneWinner() throws Exception {
        Path models = workDir.resolve("models");
        Path source = workDir.resolve("source.gguf");
        Files.writeString(source, "complete model");
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> publish = () -> {
                start.await();
                try {
                    FineTuningService.publishGguf(source, models, "job-a");
                    return true;
                } catch (java.nio.file.FileAlreadyExistsException expected) {
                    return false;
                }
            };
            var first = executor.submit(publish);
            var second = executor.submit(publish);
            start.countDown();
            assertThat(List.of(first.get(5, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(5, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
            assertThat(Files.readString(models.resolve("fine-tuning-job-a.gguf"))).isEqualTo("complete model");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedCopyLeavesNoPublicationOrPartialFile() throws Exception {
        Path models = workDir.resolve("models");
        assertThatThrownBy(() -> FineTuningService.publishGguf(workDir.resolve("missing"), models, "job-a"))
                .isInstanceOf(java.nio.file.NoSuchFileException.class);
        assertThat(Files.exists(models.resolve(".fine-tuning-job-a"))).isFalse();
    }

    @Test
    void cleanupRetainsTrainedAdapterAndMetadataForFailedOrCancelledExport() throws Exception {
        FineTuningJobRepository repository = mock(FineTuningJobRepository.class);
        FineTuningService service = new FineTuningService(mock(DatasetGeneratorService.class),
                mock(DpoGenerationService.class), repository, mock(TrainingLogBroadcaster.class),
                mock(JobTelemetryStore.class), mock(ModelRegistryService.class), mock(EvaluationService.class),
                mock(BaseModelCatalog.class), mock(GedService.class), mock(IngestedFileRepository.class),
                "phi3", mock(TrainingRunner.class), workDir.toString(), workDir.resolve("models").toString(), "");
        FineTuningJobEntity failed = oldJob("failed-export", "FAILED");
        FineTuningJobEntity cancelled = oldJob("cancelled-export", "CANCELLED");
        FineTuningJobEntity partial = oldJob("partial", "FAILED");
        for (String id : List.of("failed-export", "cancelled-export", "partial")) {
            Files.createDirectories(workDir.resolve(id).resolve("adapter"));
            Files.writeString(workDir.resolve(id).resolve("adapter/adapter_config.json"), "{}");
        }
        Files.writeString(workDir.resolve("failed-export/trained-adapter.json"), "{}");
        // Legacy jobs predate the success marker but still have usable PEFT weights.
        Files.writeString(workDir.resolve("cancelled-export/adapter/adapter_model.safetensors"), "weights");
        when(repository.findAll()).thenReturn(List.of(failed, cancelled, partial));
        service.cleanupOldJobs();
        verify(repository).deleteAll(List.of(partial));
        assertThat(Files.exists(workDir.resolve("failed-export/adapter/adapter_config.json"))).isTrue();
        assertThat(Files.exists(workDir.resolve("cancelled-export/adapter/adapter_model.safetensors"))).isTrue();
        assertThat(Files.exists(workDir.resolve("partial"))).isFalse();
    }

    private FineTuningJobEntity oldJob(String id, String status) {
        Instant old = Instant.now().minusSeconds(7200);
        return new FineTuningJobEntity(id, status, "model", "phi3", null, 1, "failed", null, 1,
                null, null, null, "export failed", "IMPORTING_MODEL", null, old, old);
    }
}
