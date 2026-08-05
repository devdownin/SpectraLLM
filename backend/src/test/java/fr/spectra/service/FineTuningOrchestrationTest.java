package fr.spectra.service;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le <b>cycle de vie complet</b> d'un job, de la soumission à son état terminal.
 *
 * <p>C'était le dernier angle mort de l'audit (constat F13 de
 * {@code docs/process/audit-finetuning.fr.md}) : les tests existants couvraient des méthodes
 * périphériques — filtre de catégories, traçabilité GED, format d'export, parsing de progression —
 * mais <b>ni la machine à états, ni le verrou d'unicité, ni l'annulation</b>. Ce sont pourtant les
 * trois mécanismes dont dépend tout le reste : un statut sauté et l'interface ment, un verrou qui
 * fuit et deux entraînements saturent la machine, une annulation mal propagée et un job continue
 * de consommer un GPU que plus personne ne regarde.
 *
 * <p><b>Ce que ce test n'a pas besoin d'un vrai trainer pour vérifier.</b> Le
 * {@link TrainingRunner} est ici un double qui rejoue des lignes réalistes — celles qu'émet
 * {@code ProgressLogger} — et produit l'artefact attendu. L'orchestration, elle, est la vraie :
 * même service, même dépôt, même magasin de trace. En l'absence de contexte Spring, {@code self}
 * est nul et {@code runAsync} s'exécute donc dans le thread appelant : le parcours est complet
 * et déterministe au retour de {@code submit()}.
 */
class FineTuningOrchestrationTest {

    @TempDir
    Path workDir;

    private FineTuningJobRepository repository;
    private DatasetGeneratorService datasetGenerator;
    private FakeTrainingRunner runner;
    private JobTelemetryStore telemetryStore;
    private FineTuningService service;

    /** Les états successivement enregistrés, dans l'ordre — la machine à états observée. */
    private final List<Status> savedStatuses = new ArrayList<>();

    private static final FineTuningRequest REQUEST = new FineTuningRequest(
            "spectra-domain", "phi3", 64, 128, 3, 2e-4, 0.8,
            false, false, false, false, 0.1);

    /**
     * Exécuteur d'entraînement de test : il rejoue des lignes, crée (ou non) l'artefact, et rend
     * le code de sortie qu'on lui demande. {@code duringTraining} permet d'agir <i>pendant</i>
     * l'exécution — c'est ainsi qu'on observe le verrou et qu'on déclenche une annulation.
     */
    private static final class FakeTrainingRunner implements TrainingRunner {
        String unavailabilityReason;
        int exitCode;
        boolean produceAdapter = true;
        Runnable duringTraining = () -> { };
        TrainingSpec receivedSpec;
        boolean sawCancellation;
        /**
         * Le crochet ne se déclenche qu'UNE fois. Il sert à tenter une soumission concurrente ;
         * si le verrou venait à disparaître, cette soumission relancerait un entraînement, qui
         * rappellerait le crochet, à l'infini — le test échouerait alors sur un débordement de
         * pile au lieu de l'assertion qui nomme le défaut.
         */
        private boolean hookFired;

        @Override
        public boolean isAvailable() {
            return unavailabilityReason == null;
        }

        @Override
        public String unavailabilityReason() {
            return unavailabilityReason;
        }

        @Override
        public int train(TrainingSpec spec, Consumer<String> onLine, BooleanSupplier cancelled)
                throws Exception {
            receivedSpec = spec;
            onLine.accept("=== Spectra Fine-Tuning ===");
            onLine.accept("  epoch=0.33  loss=1.9000");
            if (!hookFired) {
                hookFired = true;
                duringTraining.run();
            }
            if (cancelled.getAsBoolean()) {
                sawCancellation = true;
                return 130;   // convention shell : interrompu
            }
            onLine.accept("  epoch=1.00  loss=1.4000");
            onLine.accept("  epoch=1.00  eval_loss=1.5500");
            onLine.accept("  epoch=3.00  loss=0.4000");
            if (produceAdapter) {
                Files.createDirectories(spec.adapterPath());
                Files.writeString(spec.adapterPath().resolve("adapter_config.json"), "{}");
            }
            return exitCode;
        }

        @Override
        public int exportGguf(ExportSpec spec, Consumer<String> onLine, BooleanSupplier cancelled) {
            return 0;
        }

        @Override
        public boolean cancel(String jobId) {
            return true;
        }
    }

    @BeforeEach
    void setUp() {
        repository = mock(FineTuningJobRepository.class);
        datasetGenerator = mock(DatasetGeneratorService.class);
        runner = new FakeTrainingRunner();
        telemetryStore = new JobTelemetryStore(workDir.toString(), 5_000_000, 2_000_000, 2_000);

        // Dépôt à état : sans lui, chaque mise à jour repartirait du job initial et la SÉQUENCE
        // des états — ce que ce test observe — n'aurait aucun sens.
        AtomicReference<FineTuningJobEntity> stored = new AtomicReference<>();
        when(repository.save(any(FineTuningJobEntity.class))).thenAnswer(inv -> {
            FineTuningJobEntity e = inv.getArgument(0);
            stored.set(e);
            savedStatuses.add(e.toDto().status());
            return e;
        });
        when(repository.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(stored.get())
                        .filter(e -> e.getJobId().equals(inv.getArgument(0))));

        service = new FineTuningService(
                datasetGenerator, mock(DpoGenerationService.class), repository,
                mock(TrainingLogBroadcaster.class), telemetryStore, mock(ModelRegistryService.class),
                mock(BaseModelCatalog.class), mock(GedService.class),
                mock(IngestedFileRepository.class), "phi3", runner,
                workDir.toString(), workDir.resolve("models").toString(), "");

        givenPairs(3, 0.9);
    }

    /** Alimente le générateur de dataset avec {@code count} paires au score donné. */
    private void givenPairs(int count, double confidence) {
        List<TrainingPair> pairs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            pairs.add(TrainingPair.of("Question " + i + " ?", "Réponse " + i + ".",
                    "doc-" + i + ".pdf", "qa", "question_answer", confidence));
        }
        when(datasetGenerator.getAllPairs()).thenReturn(pairs);
    }

    /** Dernier état enregistré du job. */
    private FineTuningJob lastSaved() {
        ArgumentCaptor<FineTuningJobEntity> captor = ArgumentCaptor.forClass(FineTuningJobEntity.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        return captor.getValue().toDto();
    }

    @Test
    @DisplayName("parcours nominal : le job traverse les états dans l'ordre et aboutit")
    void nominalRunTraversesEveryState() throws Exception {
        String jobId = service.submit(REQUEST);

        assertThat(jobId).isNotNull();
        // L'ordre compte : c'est lui que la barre d'étapes rend, et une phase sautée se lit comme
        // un travail qui n'a pas eu lieu.
        assertThat(savedStatuses).containsSubsequence(
                Status.PENDING, Status.EXPORTING_DATASET, Status.TRAINING, Status.COMPLETED);
        assertThat(savedStatuses).doesNotContain(Status.FAILED, Status.CANCELLED);

        FineTuningJob job = lastSaved();
        assertThat(job.status()).isEqualTo(Status.COMPLETED);
        assertThat(job.datasetSize()).isEqualTo(3);
        assertThat(job.failedPhase()).isNull();
        assertThat(job.completedAt()).isNotNull();
        // Le chemin rendu est celui de l'adaptateur PEFT, pas un GGUF : l'export est une étape
        // distincte, et le dire évite de laisser croire que le modèle est déployable.
        assertThat(job.outputPath()).isEqualTo(workDir.resolve(jobId).resolve("adapter").toString());

        // Le dataset a réellement été écrit, et c'est bien celui-là qu'a reçu l'exécuteur.
        assertThat(runner.receivedSpec.datasetFile()).exists();
        assertThat(Files.readAllLines(runner.receivedSpec.datasetFile())).hasSize(3);
        assertThat(runner.receivedSpec.epochs()).isEqualTo(3);
    }

    @Test
    @DisplayName("la progression et la trace suivent l'exécution, sans trainer réel")
    void progressAndTraceFollowTheRun() {
        String jobId = service.submit(REQUEST);

        JobTelemetryStore.Telemetry telemetry = telemetryStore.read(jobId, 500);
        // Ce qui est diffusé est consigné : les lignes du trainer ET les étapes du service.
        assertThat(telemetry.logs()).extracting(JobTelemetryStore.LogLine::message)
                .anyMatch(m -> m.contains("dataset exporté — 3 paires SFT"))
                .anyMatch(m -> m.contains("epoch=1.00  loss=1.4000"));
        // La série de perte existe sur disque : c'est elle qui survit à un rechargement de page.
        assertThat(telemetry.losses()).extracting(JobTelemetryStore.LossPoint::epoch)
                .contains(0.33, 1.0, 3.0);
        assertThat(telemetry.losses()).anyMatch(p -> p.evalLoss() != null);
    }

    @Test
    @DisplayName("un seul entraînement à la fois : la deuxième soumission est refusée, puis rouverte")
    void concurrentSubmissionIsRefusedThenAllowedAgain() {
        // La soumission concurrente est tentée PENDANT l'entraînement — le seul moment où le
        // verrou est réellement tenu, et donc le seul où ce test a un sens.
        AtomicReference<String> concurrent = new AtomicReference<>("pas tenté");
        runner.duringTraining = () -> concurrent.set(service.submit(REQUEST));

        service.submit(REQUEST);

        assertThat(concurrent.get()).as("un second job accepté saturerait CPU/RAM").isNull();
        // Et le verrou est rendu : sans le `finally` de runAsync, tout entraînement ultérieur
        // serait refusé jusqu'au redémarrage du serveur.
        runner.duringTraining = () -> { };
        assertThat(service.submit(REQUEST)).isNotNull();
    }

    @Test
    @DisplayName("annulation pendant l'entraînement : le job finit CANCELLED, à la phase interrompue")
    void cancellationDuringTrainingStopsTheRun() {
        AtomicReference<String> jobId = new AtomicReference<>();
        runner.duringTraining = () -> service.cancelJob(jobId.get());
        // L'identifiant n'existe qu'après la création ; on le capte sur le premier enregistrement.
        when(repository.save(any(FineTuningJobEntity.class))).thenAnswer(inv -> {
            FineTuningJobEntity e = inv.getArgument(0);
            jobId.compareAndSet(null, e.getJobId());
            savedStatuses.add(e.toDto().status());
            when(repository.findById(e.getJobId())).thenReturn(Optional.of(e));
            return e;
        });

        service.submit(REQUEST);

        assertThat(runner.sawCancellation).as("l'annulation doit atteindre l'exécuteur").isTrue();
        FineTuningJob job = lastSaved();
        assertThat(job.status()).isEqualTo(Status.CANCELLED);
        assertThat(job.failedPhase()).isEqualTo(Status.TRAINING);
        // Aucune ligne tardive ne doit ressusciter le job : le statut terminal est définitif.
        assertThat(savedStatuses.get(savedStatuses.size() - 1)).isEqualTo(Status.CANCELLED);
    }

    @Test
    @DisplayName("code de sortie non nul : échec attribué à la phase d'entraînement")
    void nonZeroExitCodeFailsAtTrainingPhase() {
        runner.exitCode = 1;
        runner.produceAdapter = false;

        service.submit(REQUEST);

        FineTuningJob job = lastSaved();
        assertThat(job.status()).isEqualTo(Status.FAILED);
        assertThat(job.error()).contains("code 1");
        assertThat(job.failedPhase()).isEqualTo(Status.TRAINING);
    }

    @Test
    @DisplayName("sortie nulle mais adaptateur absent : l'échec n'est pas déguisé en succès")
    void missingAdapterIsNotASuccess() {
        // Un code de sortie 0 ne prouve pas qu'un modèle a été produit — c'est l'artefact qui
        // le prouve. Sans cette vérification, un job vide s'afficherait « terminé ».
        runner.produceAdapter = false;

        service.submit(REQUEST);

        FineTuningJob job = lastSaved();
        assertThat(job.status()).isEqualTo(Status.FAILED);
        assertThat(job.error()).contains("adapter_config.json");
        assertThat(job.failedPhase()).isEqualTo(Status.TRAINING);
    }

    @Test
    @DisplayName("dataset vide après filtrage : échec à l'export, l'entraînement n'est pas lancé")
    void emptyDatasetFailsBeforeTraining() {
        givenPairs(3, 0.2);   // toutes sous le seuil minConfidence=0.8

        service.submit(REQUEST);

        FineTuningJob job = lastSaved();
        assertThat(job.status()).isEqualTo(Status.FAILED);
        assertThat(job.error()).contains("Dataset vide après filtrage");
        // La phase situe l'échec à l'export : lancer un entraînement sur zéro exemple aurait
        // consommé des minutes pour rien.
        assertThat(job.failedPhase()).isEqualTo(Status.EXPORTING_DATASET);
        assertThat(runner.receivedSpec).as("l'exécuteur ne doit pas être sollicité").isNull();
        assertThat(savedStatuses).doesNotContain(Status.TRAINING);
    }

    @Test
    @DisplayName("un échec libère le verrou : le job suivant peut partir")
    void failureReleasesTheLock() {
        runner.produceAdapter = false;
        service.submit(REQUEST);

        runner.produceAdapter = true;
        String second = service.submit(REQUEST);

        // Le verrou est pris avant le travail et rendu dans un `finally` : un échec ne doit pas
        // condamner la fonctionnalité jusqu'au prochain redémarrage.
        assertThat(second).isNotNull();
        assertThat(lastSaved().status()).isEqualTo(Status.COMPLETED);
    }
}
