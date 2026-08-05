package fr.spectra.service;

import fr.spectra.dto.FineTuningRequest;
import fr.spectra.persistence.FineTuningJobRepository;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.service.dataset.DatasetGeneratorService;
import fr.spectra.service.dataset.DpoGenerationService;
import fr.spectra.service.training.TrainingRunner;
import fr.spectra.service.training.TrainingUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F1 — une soumission sans exécuteur disponible doit être <b>refusée</b>, pas acceptée puis
 * échouée.
 *
 * <p>Comportement d'origine, en conteneur : le job était accepté (HTTP 200 + {@code jobId}),
 * inscrit en base, son jeu de données généré, son répertoire de travail créé — puis
 * {@code ProcessBuilder} levait une {@code IOException} parce que l'image
 * {@code eclipse-temurin:25-jre} ne contient ni {@code scripts/} ni Python. L'utilisateur voyait
 * un job passer en {@code FAILED} après plusieurs minutes, sans cause lisible.
 *
 * <p>Ces tests figent l'inverse : refus immédiat, motif actionnable, <b>et aucun effet de
 * bord</b>. Ce dernier point est le plus facile à perdre lors d'un remaniement — un contrôle
 * placé après la création du job refuserait bien, mais laisserait une trace en base et un
 * verrou pris.
 */
class FineTuningUnavailableRunnerTest {

    @TempDir
    Path tempWorkDir;

    private static final String REASON =
            "script d'entraînement introuvable : /app/scripts/train.sh. "
                    + "Configurez spectra.fine-tuning.script si le script vit ailleurs.";

    private FineTuningJobRepository repository;
    private DatasetGeneratorService datasetGenerator;

    private FineTuningService serviceWith(TrainingRunner runner) {
        repository = mock(FineTuningJobRepository.class);
        datasetGenerator = mock(DatasetGeneratorService.class);
        return new FineTuningService(
                datasetGenerator,
                mock(DpoGenerationService.class),
                repository,
                mock(TrainingLogBroadcaster.class),
                mock(JobTelemetryStore.class),
                mock(ModelRegistryService.class),
                mock(BaseModelCatalog.class),
                mock(GedService.class),
                mock(IngestedFileRepository.class),
                "phi3",
                runner,
                tempWorkDir.toString(),
                tempWorkDir.resolve("models").toString(),
                "");
    }

    private static TrainingRunner unavailable() {
        TrainingRunner runner = mock(TrainingRunner.class);
        when(runner.isAvailable()).thenReturn(false);
        when(runner.unavailabilityReason()).thenReturn(REASON);
        return runner;
    }

    private static FineTuningRequest request() {
        return new FineTuningRequest("mon-modele", "phi3", 64, 128, 3, 2e-4,
                null, false, false, false, false, 0.0);
    }

    @Test
    @DisplayName("soumission sans exécuteur → exception dédiée, mappée en 503")
    void submitRejectsWhenRunnerUnavailable() {
        assertThatThrownBy(() -> serviceWith(unavailable()).submit(request()))
                .isInstanceOf(TrainingUnavailableException.class);
    }

    @Test
    @DisplayName("le refus porte la raison, pas un message générique")
    void rejectionCarriesTheActionableReason() {
        // C'est tout l'intérêt du 503 : un exploitant doit savoir quoi corriger sans lire les
        // journaux du serveur.
        assertThatThrownBy(() -> serviceWith(unavailable()).submit(request()))
                .hasMessageContaining("train.sh")
                .hasMessageContaining("spectra.fine-tuning.script");
    }

    @Test
    @DisplayName("aucun job n'est créé, aucun jeu de données n'est généré")
    void rejectionLeavesNoTrace() {
        FineTuningService service = serviceWith(unavailable());

        assertThatThrownBy(() -> service.submit(request()))
                .isInstanceOf(TrainingUnavailableException.class);

        verify(repository, never()).save(any());
        verify(datasetGenerator, never()).submit(any(Integer.class));
    }

    @Test
    @DisplayName("le verrou d'exclusion reste libre : un refus ne bloque pas les soumissions futures")
    void rejectionDoesNotHoldTheLock() {
        // Le contrôle est placé AVANT le compareAndSet. S'il était après, chaque refus
        // consommerait le verrou d'entraînement unique et gèlerait la fonctionnalité jusqu'au
        // redémarrage — une panne bien pire que celle qu'on corrige.
        FineTuningService service = serviceWith(unavailable());

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.submit(request()))
                    .isInstanceOf(TrainingUnavailableException.class);
        }

        // Le troisième refus est identique au premier : aucun état résiduel.
        assertThatThrownBy(() -> service.submit(request()))
                .isInstanceOf(TrainingUnavailableException.class)
                .hasMessageContaining("train.sh");
    }

    @Test
    @DisplayName("exécuteur disponible → la soumission suit son cours normal")
    void submitProceedsWhenRunnerAvailable() {
        // Contrôle de non-régression : le garde-fou ne doit pas refuser quand tout va bien.
        TrainingRunner runner = mock(TrainingRunner.class);
        when(runner.isAvailable()).thenReturn(true);

        String jobId = serviceWith(runner).submit(request());

        assertThat(jobId).isNotNull();
        verify(repository).save(any());
    }
}
