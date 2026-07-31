package fr.spectra.service.training;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Exécute l'entraînement <b>sur l'hôte</b>, par {@code ProcessBuilder}.
 *
 * <p>Mode historique, conservé pour le développement local et l'exécution hors conteneur. Il
 * suppose la présence de {@code scripts/train.sh}, de {@code scripts/export_gguf.py} et d'un
 * interpréteur Python — trois choses absentes de l'image {@code spectra-api}, d'où
 * {@link #isAvailable()}.
 *
 * <p><b>La disponibilité est mesurée, pas supposée.</b> C'est tout l'objet de F1 : auparavant le
 * service tentait l'exécution et découvrait l'absence en cours de route, après avoir accepté le
 * job et produit un jeu de données. Ici, l'absence est constatée avant l'acceptation et
 * remontée telle quelle à l'exploitant.
 */
@Component
public class ProcessTrainingRunner implements TrainingRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessTrainingRunner.class);

    private final Path workDir;
    private final String trainingScript;
    private final String exportScript;
    private final String pythonBin;

    /** Processus vivants, par job — c'est ce qui rend l'annulation effective. */
    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();

    public ProcessTrainingRunner(
            @Value("${spectra.fine-tuning.work-dir:./data/fine-tuning}") String workDir,
            @Value("${spectra.fine-tuning.script:./scripts/train.sh}") String trainingScript,
            @Value("${spectra.fine-tuning.export-script:./scripts/export_gguf.py}") String exportScript,
            @Value("${spectra.fine-tuning.python:python3}") String pythonBin) {
        this.workDir = Path.of(workDir);
        // Chemin absolu : le processus s'exécute avec workDir comme répertoire courant, un
        // chemin relatif y serait résolu depuis le mauvais endroit.
        this.trainingScript = Path.of(trainingScript).toAbsolutePath().toString();
        this.exportScript = Path.of(exportScript).toAbsolutePath().toString();
        this.pythonBin = pythonBin;
    }

    @Override
    public boolean isAvailable() {
        return unavailabilityReason() == null;
    }

    @Override
    public String unavailabilityReason() {
        if (!Files.isRegularFile(Path.of(trainingScript))) {
            return "script d'entraînement introuvable : " + trainingScript
                    + ". L'image spectra-api ne contient ni scripts/ ni Python — le fine-tuning "
                    + "s'exécute depuis un environnement hôte disposant des dépendances "
                    + "(pip install -r scripts/requirements.txt), ou via un service d'entraînement "
                    + "dédié. Configurez spectra.fine-tuning.script si le script vit ailleurs.";
        }
        return null;
    }

    @Override
    public int train(TrainingSpec spec, Consumer<String> onLine, BooleanSupplier cancelled)
            throws Exception {
        // Arguments POSITIONNELS : c'est ici, et nulle part ailleurs, que leur ordre compte.
        // Il doit suivre l'en-tête de train.sh — d'où TrainingSpec, qui empêche l'appelant de
        // se tromper de rang sans que rien n'échoue (F10).
        List<String> command = List.of(
                trainingScript,
                spec.datasetFile().toAbsolutePath().toString(),
                spec.adapterPath().toAbsolutePath().toString(),
                spec.baseHfRepo(),
                String.valueOf(spec.loraRank()),
                String.valueOf(spec.loraAlpha()),
                String.valueOf(spec.epochs()),
                String.valueOf(spec.learningRate()),
                String.valueOf(spec.packing()),
                String.valueOf(spec.dpo()),
                String.valueOf(spec.orpo()),
                String.valueOf(spec.valSplit()));
        return run(spec.jobId(), "train", command, onLine, cancelled);
    }

    @Override
    public int exportGguf(ExportSpec spec, Consumer<String> onLine, BooleanSupplier cancelled)
            throws Exception {
        List<String> command = new ArrayList<>(List.of(
                pythonBin, exportScript,
                "--adapter", spec.adapterPath().toAbsolutePath().toString(),
                "--output", spec.outputDir().toAbsolutePath().toString(),
                "--model-name", spec.modelName(),
                "--base-model", spec.baseHfRepo()));
        return run(spec.jobId(), "export", command, onLine, cancelled);
    }

    @Override
    public boolean cancel(String jobId) {
        Process process = activeProcesses.remove(jobId);
        if (process == null) {
            return false;
        }
        process.destroyForcibly();
        return true;
    }

    /**
     * Lance un sous-processus, diffuse sa sortie ligne à ligne et respecte l'annulation.
     *
     * @return le code de sortie du processus
     */
    private int run(String jobId, String label, List<String> command,
                    Consumer<String> onLine, BooleanSupplier cancelled) throws Exception {
        log.info("Job {}: commande = {}", jobId, String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true);

        Process process = pb.start();
        activeProcesses.put(jobId, process);

        try {
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled.getAsBoolean()) {
                        process.destroyForcibly();
                        break;
                    }
                    log.info("Job {} [{}]: {}", jobId, label, line);
                    if (onLine != null) onLine.accept(line);
                }
            }
            return process.waitFor();
        } finally {
            activeProcesses.remove(jobId);
        }
    }
}
