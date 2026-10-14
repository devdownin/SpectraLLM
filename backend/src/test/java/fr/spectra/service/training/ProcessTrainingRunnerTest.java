package fr.spectra.service.training;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le runner d'hôte doit dire ce qu'il peut faire <b>avant</b> qu'on le lui demande.
 *
 * <p>C'est le cœur de F1 : l'image {@code spectra-api} ne contient ni {@code scripts/} ni
 * Python. Auparavant, {@code FineTuningService} lançait {@code ProcessBuilder} sans rien
 * vérifier — le job était accepté, son jeu de données généré, puis tout mourait sur une
 * {@code IOException}. Une indisponibilité constatée à l'avance est une fonctionnalité
 * honnêtement absente ; constatée à mi-course, c'est une panne.
 */
class ProcessTrainingRunnerTest {

    @TempDir
    Path tempDir;

    private ProcessTrainingRunner runner(String script) {
        return new ProcessTrainingRunner(tempDir.toString(), script,
                tempDir.resolve("export_gguf.py").toString(), "python3");
    }

    /** Script shell qui répète ses arguments, pour observer ce que le runner transmet. */
    private Path echoScript() throws Exception {
        Path script = tempDir.resolve("train.sh");
        Files.writeString(script, "#!/bin/sh\nfor a in \"$@\"; do echo \"ARG:$a\"; done\nexit 0\n");
        script.toFile().setExecutable(true);
        return script;
    }

    @Test
    @DisplayName("script absent → indisponible, avec une raison actionnable")
    void unavailableWhenScriptMissing() {
        ProcessTrainingRunner runner = runner(tempDir.resolve("absent.sh").toString());

        assertThat(runner.isAvailable()).isFalse();
        assertThat(runner.unavailabilityReason())
                .contains("absent.sh")            // ce qui manque
                .contains("spectra.fine-tuning.script");   // et comment y remédier
    }

    @Test
    @DisplayName("le motif nomme la commande qui rend le fine-tuning disponible")
    void unavailabilityReasonNamesTheRemedy() {
        // Le script existe pourtant dans le dépôt : dire « introuvable » sans plus décrit une
        // absence que l'exploitant ira chercher en vain. Ce qui manque n'est pas le fichier,
        // c'est le service qui sait l'exécuter — et le démarrer tient en une commande. La
        // figer ici, c'est empêcher qu'un remaniement ne rende le refus à nouveau muet.
        String reason = runner(tempDir.resolve("absent.sh").toString()).unavailabilityReason();

        assertThat(reason).contains("start.sh --trainer");
    }

    @Test
    @DisplayName("script présent → disponible, sans raison d'indisponibilité")
    void availableWhenScriptPresent() throws Exception {
        ProcessTrainingRunner runner = runner(echoScript().toString());

        assertThat(runner.isAvailable()).isTrue();
        assertThat(runner.unavailabilityReason()).isNull();
    }

    @Test
    @DisplayName("les onze arguments d'entraînement partent dans l'ordre attendu par train.sh")
    void passesPositionalArgumentsInScriptOrder() throws Exception {
        // Le seul endroit du code où l'ordre compte encore. Le figer ici, c'est empêcher qu'un
        // paramètre inséré au mauvais rang décale silencieusement tous les suivants (F10) :
        // l'entraînement tournerait, sur d'autres hyperparamètres que ceux demandés.
        List<String> lines = new ArrayList<>();
        TrainingSpec spec = new TrainingSpec("job-1",
                tempDir.resolve("dataset.jsonl"), tempDir.resolve("adapter"),
                "microsoft/Phi-3-mini-4k-instruct",
                64, 128, 3, 2e-4, true, false, true, 0.1);

        int exit = runner(echoScript().toString()).train(spec, lines::add, () -> false);

        assertThat(exit).isZero();
        assertThat(lines).hasSize(11);   // $1..$11, cf. en-tête de train.sh
        assertThat(lines.get(2)).isEqualTo("ARG:microsoft/Phi-3-mini-4k-instruct");
        assertThat(lines.subList(3, 11)).containsExactly(
                "ARG:64", "ARG:128", "ARG:3", "ARG:2.0E-4",
                "ARG:true", "ARG:false", "ARG:true", "ARG:0.1");
        assertThat(lines.get(0)).endsWith("dataset.jsonl");
        assertThat(lines.get(1)).endsWith("adapter");
    }

    @Test
    @DisplayName("le code de sortie du script est remonté tel quel")
    void propagatesExitCode() throws Exception {
        Path script = tempDir.resolve("train.sh");
        Files.writeString(script, "#!/bin/sh\nexit 42\n");
        script.toFile().setExecutable(true);

        int exit = runner(script.toString()).train(
                new TrainingSpec("job-2", tempDir.resolve("d"), tempDir.resolve("a"), "base",
                        8, 16, 1, 1e-4, false, false, false, 0.0),
                line -> { }, () -> false);

        assertThat(exit).isEqualTo(42);
    }

    @Test
    @DisplayName("l'annulation interrompt le processus au lieu de le laisser courir")
    void cancellationStopsTheProcess() throws Exception {
        // Script long : sans interruption, il tournerait bien au-delà de la durée du test.
        Path script = tempDir.resolve("train.sh");
        Files.writeString(script, "#!/bin/sh\ni=0\nwhile [ $i -lt 200 ]; do echo \"step $i\"; "
                + "i=$((i+1)); sleep 0.05; done\n");
        script.toFile().setExecutable(true);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        List<String> lines = new ArrayList<>();

        runner(script.toString()).train(
                new TrainingSpec("job-3", tempDir.resolve("d"), tempDir.resolve("a"), "base",
                        8, 16, 1, 1e-4, false, false, false, 0.0),
                line -> { lines.add(line); if (lines.size() >= 3) cancelled.set(true); },
                cancelled::get);

        // Quelques lignes lues, puis arrêt — et non les 200 que le script produirait.
        assertThat(lines).hasSizeLessThan(200);
    }

    @Test
    @DisplayName("annuler un job inconnu ne prétend pas avoir interrompu quelque chose")
    void cancelUnknownJobReportsNothingStopped() {
        assertThat(runner("/inexistant").cancel("jamais-lancé")).isFalse();
    }
}
