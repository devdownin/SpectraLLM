package fr.spectra.service;

import fr.spectra.dto.FineTuningJob;
import fr.spectra.dto.FineTuningJob.Status;
import fr.spectra.dto.FineTuningRequest;
import fr.spectra.persistence.FineTuningJobEntity;
import fr.spectra.persistence.FineTuningJobRepository;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.service.dataset.DatasetGeneratorService;
import fr.spectra.service.dataset.DpoGenerationService;
import fr.spectra.service.training.TrainingRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>Où</b> un job a échoué, et non seulement <b>qu'il</b> a échoué.
 *
 * <p>Sans cette information, l'échec était un point sans lieu : {@code failed()} écrasait l'étape
 * courante par « Échoué » et le statut valait {@code FAILED}, si bien que l'interface plaçait tous
 * les échecs sur la même étape du pipeline — un dataset vide au filtrage s'affichait comme un
 * échec d'import, c'est-à-dire l'inverse de ce qui s'était passé (constat S5 de
 * {@code docs/process/audit-suivi-finetuning-ui.fr.md}).</p>
 */
class FineTuningFailurePhaseTest {

    @TempDir
    Path tempWorkDir;

    private static final FineTuningRequest REQUEST = new FineTuningRequest(
            "spectra-domain", "phi3", 64, 128, 3, 2e-4, 0.8,
            false, false, false, false, 0.1, null);

    private static FineTuningJob pending() {
        return FineTuningJob.pending("job-1", REQUEST);
    }

    @Test
    @DisplayName("l'échec retient la phase que le job portait")
    void failureRecordsTheReachedPhase() {
        FineTuningJob exporting = pending().withStatus(Status.EXPORTING_DATASET, "Export du dataset...");

        FineTuningJob failed = exporting.failed("Dataset vide après filtrage");

        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.failedPhase()).isEqualTo(Status.EXPORTING_DATASET);
    }

    @Test
    @DisplayName("un second échec ne déplace pas la phase du premier")
    void secondFailureKeepsTheOriginalPhase() {
        FineTuningJob failedWhileTraining = pending()
                .withStatus(Status.TRAINING, "Lancement de l'entraînement...")
                .failed("Le script d'entraînement a retourné le code 1");

        FineTuningJob refailed = failedWhileTraining.failed("Annulé par l'utilisateur");

        // Réécrire la phase la ferait remonter à l'endroit du SECOND appel — la réconciliation au
        // démarrage, par exemple, réétiquetterait tous les échecs comme survenus « en attente ».
        assertThat(refailed.failedPhase()).isEqualTo(Status.TRAINING);
    }

    @Test
    @DisplayName("un job qui aboutit ne porte aucune phase d'échec")
    void completionCarriesNoFailurePhase() {
        FineTuningJob completed = pending()
                .withStatus(Status.IMPORTING_MODEL, "Fusion LoRA + conversion GGUF…")
                .completed("/data/models/spectra-domain.gguf");

        assertThat(completed.failedPhase()).isNull();
    }

    @Test
    @DisplayName("la phase survit à l'aller-retour en base")
    void failurePhaseRoundTripsThroughThePersistedEntity() {
        FineTuningJob failed = pending()
                .withStatus(Status.IMPORTING_MODEL, "Fusion LoRA + conversion GGUF…")
                .failed("Fichier GGUF introuvable après conversion");

        FineTuningJob reloaded = FineTuningJobEntity.fromDto(failed).toDto();

        assertThat(reloaded.failedPhase()).isEqualTo(Status.IMPORTING_MODEL);
    }

    @Test
    @DisplayName("une phase illisible en base n'empêche pas de relire le job")
    void unknownPersistedPhaseDegradesToNull() {
        // Colonne écrite par une version ultérieure, ou éditée à la main : rendre null vaut mieux
        // que faire échouer la lecture de TOUT l'historique sur un IllegalArgumentException.
        FineTuningJobEntity corrupted = new FineTuningJobEntity(
                "job-1", "FAILED", "spectra-domain", "phi3", "{}", 0, "Échoué",
                null, 3, null, null, null, "boum", "PHASE_INCONNUE", null,
                Instant.now(), Instant.now());

        FineTuningJob dto = corrupted.toDto();

        assertThat(dto.failedPhase()).isNull();
        assertThat(dto.status()).isEqualTo(Status.FAILED);
        assertThat(dto.error()).isEqualTo("boum");
    }

    @Test
    @DisplayName("une annulation retient elle aussi la phase interrompue")
    void cancellationRecordsThePhaseItInterrupted() {
        FineTuningJobRepository repository = mock(FineTuningJobRepository.class);
        TrainingRunner runner = mock(TrainingRunner.class);
        FineTuningService service = new FineTuningService(
                mock(DatasetGeneratorService.class), mock(DpoGenerationService.class),
                repository, mock(TrainingLogBroadcaster.class), mock(JobTelemetryStore.class),
                mock(ModelRegistryService.class),
                mock(EvaluationService.class),
                mock(BaseModelCatalog.class), mock(GedService.class),
                mock(IngestedFileRepository.class), "phi3", runner,
                tempWorkDir.toString(), tempWorkDir.resolve("models").toString(), "");

        FineTuningJob training = pending().withStatus(Status.TRAINING, "Lancement de l'entraînement...");
        when(repository.findById("job-1"))
                .thenReturn(Optional.of(FineTuningJobEntity.fromDto(training)));

        assertThat(service.cancelJob("job-1")).isTrue();

        ArgumentCaptor<FineTuningJobEntity> saved = ArgumentCaptor.forClass(FineTuningJobEntity.class);
        verify(repository).save(saved.capture());
        FineTuningJob cancelled = saved.getValue().toDto();
        // « Arrêté pendant l'entraînement » et « arrêté avant qu'il ne commence » n'ont pas la
        // même conséquence : le premier a consommé des heures de calcul, le second non.
        assertThat(cancelled.failedPhase()).isEqualTo(Status.TRAINING);
        // Et un arrêt volontaire n'est pas un incident : le confondre avec un échec envoyait
        // l'utilisateur enquêter sur ce qu'il venait lui-même de décider.
        assertThat(cancelled.status()).isEqualTo(Status.CANCELLED);
    }

    @Test
    @DisplayName("un job annulé est terminal : ni réécrit par une ligne tardive, ni réconcilié")
    void cancelledIsTerminal() {
        assertThat(Status.CANCELLED.isTerminal()).isTrue();
        assertThat(Status.FAILED.isTerminal()).isTrue();
        assertThat(Status.COMPLETED.isTerminal()).isTrue();
        // Sans cela, le redémarrage suivant « réconcilierait » un job annulé en FAILED, et une
        // ligne de progression tardive le remettrait en TRAINING.
        assertThat(Status.TRAINING.isTerminal()).isFalse();
        assertThat(Status.PENDING.isTerminal()).isFalse();
    }
}
