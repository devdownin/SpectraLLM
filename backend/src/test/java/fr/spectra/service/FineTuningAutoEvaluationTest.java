package fr.spectra.service;

import fr.spectra.dto.EvaluationRequest;
import fr.spectra.dto.FineTuningJob;
import fr.spectra.dto.FineTuningJob.Status;
import fr.spectra.dto.FineTuningRequest;
import fr.spectra.model.TrainingPair;
import fr.spectra.persistence.FineTuningJobEntity;
import fr.spectra.persistence.FineTuningJobRepository;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.service.dataset.DatasetGeneratorService;
import fr.spectra.service.dataset.DpoGenerationService;
import fr.spectra.service.training.ExportSpec;
import fr.spectra.service.training.TrainingRunner;
import fr.spectra.service.training.TrainingSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Enchaînement de l'évaluation après un entraînement réussi.
 *
 * <p>Un job se terminait sur un « COMPLETED » qui ne disait rien de la <b>qualité</b> obtenue.
 * L'évaluation LLM-as-a-judge existait pourtant de bout en bout — jusqu'au champ
 * {@code EvaluationReport.jobId}, prévu pour rattacher un rapport au job qui avait produit le
 * modèle, et qui n'a jamais eu d'appelant : personne ne le renseignait. Il fallait ouvrir un
 * autre écran et retaper le nom du modèle.
 */
class FineTuningAutoEvaluationTest {

    @TempDir
    Path workDir;

    private FineTuningJobRepository repository;
    private EvaluationService evaluationService;
    private TrainingLogBroadcaster broadcaster;
    private FineTuningService service;
    private final AtomicReference<FineTuningJobEntity> stored = new AtomicReference<>();

    /** Entraîne, puis produit l'adaptateur ET le GGUF attendus : le chemin nominal complet. */
    private static final class SuccessfulRunner implements TrainingRunner {
        @Override public boolean isAvailable() { return true; }
        @Override public String unavailabilityReason() { return null; }

        @Override
        public int train(TrainingSpec spec, Consumer<String> onLine, BooleanSupplier cancelled)
                throws Exception {
            onLine.accept("  epoch=3.00  loss=0.4000");
            Files.createDirectories(spec.adapterPath());
            Files.writeString(spec.adapterPath().resolve("adapter_config.json"), "{}");
            return 0;
        }

        @Override
        public int exportGguf(ExportSpec spec, Consumer<String> onLine, BooleanSupplier cancelled)
                throws Exception {
            Files.createDirectories(spec.outputDir());
            Files.writeString(spec.outputDir().resolve("model.gguf"), "gguf");
            return 0;
        }

        @Override public boolean cancel(String jobId) { return true; }
    }

    @BeforeEach
    void setUp() {
        repository = mock(FineTuningJobRepository.class);
        evaluationService = mock(EvaluationService.class);
        broadcaster = mock(TrainingLogBroadcaster.class);
        DatasetGeneratorService datasetGenerator = mock(DatasetGeneratorService.class);
        when(datasetGenerator.getAllPairs()).thenReturn(List.of(
                TrainingPair.of("Question ?", "Réponse.", "doc.pdf", "qa", "question_answer", 0.9)));

        when(repository.save(any(FineTuningJobEntity.class))).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(repository.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(stored.get())
                        .filter(e -> e.getJobId().equals(inv.getArgument(0))));

        service = new FineTuningService(
                datasetGenerator, mock(DpoGenerationService.class), repository,
                broadcaster, mock(JobTelemetryStore.class), mock(ModelRegistryService.class),
                evaluationService, mock(BaseModelCatalog.class), mock(GedService.class),
                mock(IngestedFileRepository.class), "phi3", new SuccessfulRunner(),
                workDir.toString(), workDir.resolve("models").toString(), "");
    }

    private static FineTuningRequest request(boolean autoEvaluate, boolean exportGguf) {
        return new FineTuningRequest("spectra-domain", "phi3", 64, 128, 1, 2e-4, 0.5,
                false, false, false, exportGguf, 0.1, autoEvaluate);
    }

    @Test
    @DisplayName("le modèle enregistré est évalué, et le rapport porte l'identifiant du job")
    void registeredModelIsEvaluatedAndLinkedBack() {
        when(evaluationService.submit(any())).thenReturn("eval-7");

        String jobId = service.submit(request(true, true));

        ArgumentCaptor<EvaluationRequest> captor = ArgumentCaptor.forClass(EvaluationRequest.class);
        verify(evaluationService).submit(captor.capture());
        // Le jobId transmis est ce qui remplit enfin EvaluationReport.jobId : sans lui, le
        // rapport existe mais flotte, sans rattachement à l'entraînement qui l'a motivé.
        assertThat(captor.getValue().jobId()).isEqualTo(jobId);
        assertThat(captor.getValue().modelName()).isEqualTo("spectra-domain");

        FineTuningJob job = stored.get().toDto();
        assertThat(job.status()).isEqualTo(Status.COMPLETED);
        assertThat(job.evaluationId()).isEqualTo("eval-7");
    }

    @Test
    @DisplayName("le job reste COMPLETED : on n'attend pas la fin de l'évaluation pour le dire")
    void jobIsTerminalBeforeTheEvaluationFinishes() {
        // L'évaluation dure plusieurs minutes. Retarder le statut jusqu'à son terme présenterait
        // un entraînement réussi comme encore en cours — et l'écraserait s'il échouait.
        when(evaluationService.submit(any())).thenReturn("eval-7");

        service.submit(request(true, true));

        assertThat(stored.get().toDto().status()).isEqualTo(Status.COMPLETED);
        assertThat(stored.get().toDto().outputPath()).endsWith("spectra-domain.gguf");
    }

    @Test
    @DisplayName("sans demande explicite, rien n'est évalué")
    void withoutTheFlagNothingIsEvaluated() {
        service.submit(request(false, true));

        verify(evaluationService, never()).submit(any());
        assertThat(stored.get().toDto().evaluationId()).isNull();
    }

    @Test
    @DisplayName("évaluer sans export GGUF est refusé À LA SOUMISSION, pas des heures plus tard")
    void evaluationWithoutExportIsRefusedUpFront() {
        // Un adaptateur LoRA seul n'est pas servable : l'évaluation aurait interrogé le modèle
        // ACTIF tout en attribuant le score au nouveau. Un chiffre faux, présenté comme vrai —
        // pire que pas de chiffre du tout.
        assertThatThrownBy(() -> service.submit(request(true, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("export GGUF");

        verify(repository, never()).save(any());
        verify(evaluationService, never()).submit(any());
    }

    @Test
    @DisplayName("un refus ne consomme pas le verrou d'unicité")
    void refusalDoesNotLeakTheTrainingLock() {
        // Le contrôle a lieu avant la prise du verrou : sans cela, une requête invalide
        // interdirait tout entraînement jusqu'au redémarrage.
        assertThatThrownBy(() -> service.submit(request(true, false)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(service.submit(request(false, true))).isNotNull();
    }

    @Test
    @DisplayName("une évaluation qui ne démarre pas n'échoue pas le job — le modèle est bien là")
    void failedEvaluationDoesNotFailTheJob() {
        when(evaluationService.submit(any()))
                .thenThrow(new IllegalStateException("modèle-juge injoignable"));

        service.submit(request(true, true));

        FineTuningJob job = stored.get().toDto();
        assertThat(job.status()).isEqualTo(Status.COMPLETED);
        assertThat(job.evaluationId()).isNull();
        // Le dire, en revanche, est indispensable : sinon l'utilisateur attend un score qui ne
        // viendra jamais, sans savoir pourquoi.
        ArgumentCaptor<String> warn = ArgumentCaptor.forClass(String.class);
        verify(broadcaster).jobWarn(any(), warn.capture());
        assertThat(warn.getValue()).contains("modèle-juge injoignable");
    }
}
