package fr.spectra.service;

import fr.spectra.dto.FineTuningJob;
import fr.spectra.dto.FineTuningRequest;
import fr.spectra.persistence.FineTuningJobEntity;
import fr.spectra.persistence.FineTuningJobRepository;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.service.dataset.DatasetGeneratorService;
import fr.spectra.service.dataset.DpoGenerationService;
import fr.spectra.service.training.TrainingRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ce que la sortie du trainer produit réellement comme <b>suivi</b> : époque, loss, et volume
 * diffusé sur le flux SSE.
 *
 * <p>Ces invariants n'étaient couverts par aucun test, et deux d'entre eux étaient faux (cf.
 * {@code docs/process/audit-suivi-finetuning-ui.fr.md}, constats S3 et S12) :
 * l'époque était tronquée à l'entier — donc {@code 0} pendant toute la première époque, que
 * l'interface lisait comme « progression inconnue » et masquait — et chaque rafraîchissement de
 * barre {@code tqdm} devenait un évènement SSE.</p>
 */
class FineTuningProgressTrackingTest {

    @TempDir
    Path tempWorkDir;

    private static final String JOB_ID = "job-1";

    private FineTuningJobRepository repository;
    private TrainingLogBroadcaster broadcaster;
    private JobTelemetryStore telemetryStore;
    private FineTuningService service;

    @BeforeEach
    void setUp() {
        repository = mock(FineTuningJobRepository.class);
        broadcaster = mock(TrainingLogBroadcaster.class);
        telemetryStore = mock(JobTelemetryStore.class);
        service = new FineTuningService(
                mock(DatasetGeneratorService.class),
                mock(DpoGenerationService.class),
                repository,
                broadcaster,
                telemetryStore,
                mock(ModelRegistryService.class),
                mock(BaseModelCatalog.class),
                mock(GedService.class),
                mock(IngestedFileRepository.class),
                "phi3",
                mock(TrainingRunner.class),
                tempWorkDir.toString(),
                tempWorkDir.resolve("models").toString(),
                "");

        FineTuningRequest request = new FineTuningRequest(
                "spectra-domain", "phi3", 64, 128, 3, 2e-4, 0.8,
                false, false, false, false, 0.1);

        // Dépôt à état : chaque ligne de sortie met à jour le job à partir du PRÉCÉDENT. Un mock
        // renvoyant toujours le job initial masquerait le cumul des valeurs — précisément ce que
        // ces tests vérifient (une loss conservée d'une ligne à l'autre).
        AtomicReference<FineTuningJobEntity> stored = new AtomicReference<>(
                FineTuningJobEntity.fromDto(FineTuningJob.pending(JOB_ID, request)));
        when(repository.findById(JOB_ID)).thenAnswer(inv -> Optional.of(stored.get()));
        when(repository.save(any(FineTuningJobEntity.class))).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
    }

    /** Rejoue une ligne de sortie du trainer par le chemin de production (diffusion + parsing). */
    private void emit(String line) throws Exception {
        Method m = FineTuningService.class.getDeclaredMethod(
                "onProcessLine", String.class, String.class, String.class);
        m.setAccessible(true);
        m.invoke(service, JOB_ID, "train", line);
    }

    /** Dernier état de job effectivement enregistré. */
    private FineTuningJob lastSaved() {
        ArgumentCaptor<FineTuningJobEntity> captor = ArgumentCaptor.forClass(FineTuningJobEntity.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        return captor.getValue().toDto();
    }

    @Test
    @DisplayName("l'époque fractionnaire est conservée — la première époque n'est plus « 0 »")
    void fractionalEpochSurvivesParsing() throws Exception {
        emit("  epoch=0.97  loss=1.2345");

        FineTuningJob job = lastSaved();
        assertThat(job.currentEpoch()).isEqualTo(0.97);
        assertThat(job.loss()).isEqualTo(1.2345);
        // 0.97 époque consommée, c'est la PREMIÈRE qui travaille : l'utilisateur doit lire 1/3,
        // pas 0/3 — qui se lit comme un blocage.
        assertThat(job.currentStep()).isEqualTo("Entraînement epoch 1/3");
    }

    @Test
    @DisplayName("eval_loss n'est pas enregistrée comme loss d'entraînement, et ne l'efface pas")
    void evalLossKeepsItsOwnField() throws Exception {
        emit("  epoch=1.00  loss=0.8000");
        emit("  epoch=1.00  eval_loss=0.9500");

        FineTuningJob job = lastSaved();
        assertThat(job.loss()).isEqualTo(0.8000);
        assertThat(job.evalLoss()).isEqualTo(0.9500);
    }

    @Test
    @DisplayName("l'époque atteinte plafonne au total configuré")
    void epochLabelNeverExceedsTotal() throws Exception {
        // Une dernière étape peut logger 3.0000000001 (flottants) ; « epoch 4/3 » serait absurde.
        emit("  epoch=3.00  loss=0.4000");

        assertThat(lastSaved().currentStep()).isEqualTo("Entraînement epoch 3/3");
    }

    @Test
    @DisplayName("une rafale de barre de progression est limitée, les lignes utiles passent toutes")
    void progressBarBurstIsThrottled() throws Exception {
        for (int i = 1; i <= 50; i++) {
            emit("Downloading model.safetensors:  " + i + "%|██        | " + i + "/100 [00:03<00:30, 3.2MB/s]");
        }
        // Au plus une ligne de barre par seconde : la rafale entière tient dans le même intervalle.
        verify(broadcaster, times(1)).jobInfo(eq(JOB_ID), any());

        emit("Début de l'entraînement (3 époque(s), LoRA rank=64)...");
        emit("  epoch=0.33  loss=1.9000");
        verify(broadcaster, times(3)).jobInfo(eq(JOB_ID), any());
    }

    @Test
    @DisplayName("la fin d'un téléchargement (100 %) n'est jamais avalée par le limiteur")
    void completionLineAlwaysPasses() throws Exception {
        emit("Downloading model.safetensors:  42%|████      | 42/100 [00:03<00:30, 3.2MB/s]");
        emit("Downloading model.safetensors: 100%|██████████| 100/100 [00:31<00:00, 3.2MB/s]");

        // Sans exception pour « 100% », la barre resterait figée à 42 % pour toujours.
        verify(broadcaster, times(2)).jobInfo(eq(JOB_ID), any());
    }

    @Test
    @DisplayName("ce qui est diffusé est aussi consigné dans la trace du job")
    void everyBroadcastLineIsAlsoRecorded() throws Exception {
        // Un seul point de sortie pour les deux canaux : sans cela, ce qu'on relit après un
        // rechargement finirait par diverger de ce qu'on a vu passer en direct.
        emit("  epoch=0.33  loss=1.9000");

        verify(broadcaster).jobInfo(eq(JOB_ID), eq("  epoch=0.33  loss=1.9000"));
        verify(telemetryStore).appendLog(eq(JOB_ID), eq("INFO"), eq("  epoch=0.33  loss=1.9000"));
        // La série n'existe QUE sur disque : le job ne porte qu'une loss scalaire, écrasée à
        // chaque ligne.
        verify(telemetryStore).appendLossPoint(JOB_ID, 0.33, 1.9, null);
    }

    @Test
    @DisplayName("une ligne vide n'est pas diffusée")
    void blankLinesAreDropped() throws Exception {
        emit("   ");

        verify(broadcaster, never()).jobInfo(any(), any());
        verify(telemetryStore, never()).appendLog(any(), any(), any());
    }
}
