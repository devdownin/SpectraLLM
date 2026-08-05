package fr.spectra.service;

import fr.spectra.dto.FineTuningRequest;
import fr.spectra.dto.FineTuningJob;
import fr.spectra.dto.FineTuningJob.Status;
import fr.spectra.persistence.FineTuningJobEntity;
import fr.spectra.persistence.FineTuningJobRepository;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.service.dataset.DatasetGeneratorService;
import fr.spectra.service.dataset.DpoGenerationService;
import fr.spectra.service.training.TrainingRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Un redémarrage ne doit laisser derrière lui ni job fantôme, ni entraînement orphelin.
 *
 * <p><b>Deux dégâts distincts</b>, et le second ne se voyait pas avant le mode conteneur :</p>
 *
 * <ol>
 *   <li>Un job resté {@code TRAINING} alors que plus rien ne tourne — l'interface affiche
 *       indéfiniment une progression figée.</li>
 *   <li>Une exécution qui, elle, <b>tourne encore</b>. En mode hôte c'est impossible (le
 *       sous-processus meurt avec la JVM), mais en mode HTTP l'entraînement vit dans un autre
 *       conteneur qui n'apprend le départ de son client qu'en tentant d'écrire — donc à la
 *       prochaine ligne de journal. Un entraînement silencieux consommerait CPU, RAM et GPU
 *       pendant des heures pour un job déjà déclaré perdu.</li>
 * </ol>
 *
 * <p>La réconciliation existait pour (1) sans être couverte par un test ; (2) est le complément
 * qu'exige le mode conteneur.</p>
 */
class FineTuningReconciliationTest {

    @TempDir
    Path tempWorkDir;

    private FineTuningJobRepository repository;
    private TrainingRunner runner;

    private FineTuningService serviceWith(FineTuningJob... jobs) {
        repository = mock(FineTuningJobRepository.class);
        runner = mock(TrainingRunner.class);
        when(repository.findAll()).thenReturn(
                java.util.Arrays.stream(jobs).map(FineTuningJobEntity::fromDto).toList());
        return new FineTuningService(
                mock(DatasetGeneratorService.class), mock(DpoGenerationService.class),
                repository, mock(TrainingLogBroadcaster.class), mock(JobTelemetryStore.class),
                mock(ModelRegistryService.class),
                mock(BaseModelCatalog.class), mock(GedService.class),
                mock(IngestedFileRepository.class), "phi3", runner,
                tempWorkDir.toString(), tempWorkDir.resolve("models").toString(), "");
    }

    private static FineTuningJob jobWith(String id, Status status) {
        FineTuningJob pending = FineTuningJob.pending(id, new FineTuningRequest(
                "mon-modele", "phi3", 64, 128, 3, 2e-4, null, false, false, false, false, 0.0));
        return status == Status.PENDING ? pending : pending.withStatus(status, "en cours");
    }

    private List<FineTuningJobEntity> savedEntities() {
        ArgumentCaptor<FineTuningJobEntity> captor = ArgumentCaptor.forClass(FineTuningJobEntity.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"PENDING", "EXPORTING_DATASET", "TRAINING", "IMPORTING_MODEL"})
    @DisplayName("tout état non terminal devient FAILED, avec la cause")
    void nonTerminalJobsAreFailed(Status status) {
        FineTuningService service = serviceWith(jobWith("job-1", status));

        service.reconcileInterruptedJobs();

        assertThat(savedEntities()).singleElement()
                .extracting(FineTuningJobEntity::toDto)
                .satisfies(j -> {
                    assertThat(j.status()).isEqualTo(Status.FAILED);
                    // Sans cause explicite, l'utilisateur ne peut pas distinguer un
                    // redémarrage d'un échec d'entraînement — deux actions opposées.
                    assertThat(j.error()).contains("redémarrage");
                });
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"COMPLETED", "FAILED"})
    @DisplayName("un job terminé n'est jamais réécrit")
    void terminalJobsAreLeftAlone(Status status) {
        FineTuningService service = serviceWith(jobWith("job-1", status));

        service.reconcileInterruptedJobs();

        // Réécrire un COMPLETED effacerait un modèle livré de l'historique.
        verify(repository, never()).save(any());
        verify(runner, never()).cancel(any());
    }

    @Test
    @DisplayName("l'exécution est annulée auprès du runner — le mode conteneur en dépend")
    void interruptedJobsAreCancelledOnTheRunner() {
        // En mode HTTP, c'est le seul signal qui atteigne le conteneur d'entraînement : sans
        // lui, il continuerait à consommer des ressources pour un job déjà perdu.
        FineTuningService service = serviceWith(jobWith("job-1", Status.TRAINING));

        service.reconcileInterruptedJobs();

        verify(runner).cancel("job-1");
    }

    @Test
    @DisplayName("un runner injoignable n'empêche pas la réconciliation d'aboutir")
    void runnerFailureDoesNotBlockReconciliation() {
        // Le trainer peut être arrêté, ou démarrer plus lentement que l'API. Marquer les jobs
        // reste la partie qui compte ; l'annulation est au mieux consultative.
        FineTuningService service = serviceWith(jobWith("job-1", Status.TRAINING));
        when(runner.cancel(any())).thenThrow(new RuntimeException("trainer injoignable"));

        service.reconcileInterruptedJobs();

        assertThat(savedEntities()).singleElement()
                .extracting(e -> e.toDto().status()).isEqualTo(Status.FAILED);
    }

    @Test
    @DisplayName("un runner qui lève sur le premier job n'empêche pas de traiter les suivants")
    void runnerFailureDoesNotAbortTheLoop() {
        // Le try/catch de réconciliation englobe la boucle : une exception non contenue y
        // interromprait le traitement des jobs restants, qui resteraient TRAINING à jamais —
        // exactement le fantôme que cette méthode existe pour éliminer.
        FineTuningService service = serviceWith(
                jobWith("job-1", Status.TRAINING),
                jobWith("job-2", Status.TRAINING));
        when(runner.cancel("job-1")).thenThrow(new RuntimeException("trainer injoignable"));

        service.reconcileInterruptedJobs();

        assertThat(savedEntities()).hasSize(2);
    }

    @Test
    @DisplayName("plusieurs jobs orphelins sont tous traités")
    void allOrphansAreHandled() {
        FineTuningService service = serviceWith(
                jobWith("job-1", Status.TRAINING),
                jobWith("job-2", Status.COMPLETED),
                jobWith("job-3", Status.EXPORTING_DATASET));

        service.reconcileInterruptedJobs();

        assertThat(savedEntities()).hasSize(2);
        verify(runner).cancel("job-1");
        verify(runner).cancel("job-3");
        verify(runner, never()).cancel("job-2");
    }
}
