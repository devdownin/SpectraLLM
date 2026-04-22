package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.dto.FineTuningJob;
import fr.spectra.dto.FineTuningJob.Status;
import fr.spectra.dto.FineTuningRequest;
import fr.spectra.model.TrainingPair;
import fr.spectra.service.dataset.DatasetGeneratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestre le fine-tuning : export dataset → entraînement externe → production du modèle GGUF.
 *
 * Le processus d'entraînement est délégué à un script externe configurable
 * (Unsloth, Axolotl, ou tout autre outil compatible JSONL).
 */
@Service
public class FineTuningService {

    private static final Logger log = LoggerFactory.getLogger(FineTuningService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final DatasetGeneratorService datasetGenerator;
    private final LlmChatClient llmClient;
    private final Path workDir;
    private final String trainingScript;

    private final Map<String, FineTuningJob> jobs = new ConcurrentHashMap<>();

    public FineTuningService(DatasetGeneratorService datasetGenerator,
                             LlmChatClient llmClient,
                             @Value("${spectra.fine-tuning.work-dir:./data/fine-tuning}") String workDir,
                             @Value("${spectra.fine-tuning.script:./scripts/train.sh}") String trainingScript) {
        this.datasetGenerator = datasetGenerator;
        this.llmClient = llmClient;
        this.workDir = Path.of(workDir);
        this.trainingScript = trainingScript;
    }

    /**
     * Lance un job de fine-tuning asynchrone.
     */
    public String submit(FineTuningRequest request) {
        String jobId = UUID.randomUUID().toString();

        // Résoudre le modèle de base
        FineTuningRequest resolved = new FineTuningRequest(
                request.modelName(),
                request.baseModel() != null ? request.baseModel() : llmClient.getDefaultModel(),
                request.loraRank(),
                request.loraAlpha(),
                request.epochs(),
                request.learningRate(),
                request.minConfidence(),
                request.packingEnabled(),
                request.dpoEnabled()
        );

        jobs.put(jobId, FineTuningJob.pending(jobId, resolved));
        runAsync(jobId, resolved);
        return jobId;
    }

    public FineTuningJob getJob(String jobId) {
        return jobs.get(jobId);
    }

    public List<FineTuningJob> getAllJobs() {
        return new ArrayList<>(jobs.values());
    }

    @Async
    protected void runAsync(String jobId, FineTuningRequest request) {
        try {
            // ── Étape 1 : Export du dataset filtré ──
            updateJob(jobId, j -> j.withStatus(Status.EXPORTING_DATASET, "Export du dataset..."));

            Path jobDir = workDir.resolve(jobId);
            Files.createDirectories(jobDir);

            Path datasetFile = exportFilteredDataset(jobDir, request.minConfidence());
            int datasetSize = countLines(datasetFile);
            updateJob(jobId, j -> j.withDatasetSize(datasetSize));

            if (datasetSize == 0) {
                updateJob(jobId, j -> j.failed("Dataset vide après filtrage (minConfidence=" + request.minConfidence() + ")"));
                return;
            }

            log.info("Job {}: dataset exporté ({} paires)", jobId, datasetSize);

            // ── Étape 2 : Lancement de l'entraînement externe ──
            updateJob(jobId, j -> j.withStatus(Status.TRAINING, "Lancement de l'entraînement..."));

            Path adapterPath = jobDir.resolve("adapter.gguf");

            int exitCode = runTrainingProcess(jobId, request, datasetFile, adapterPath);

            if (exitCode != 0) {
                updateJob(jobId, j -> j.failed("Le script d'entraînement a retourné le code " + exitCode));
                return;
            }

            if (!Files.exists(adapterPath)) {
                updateJob(jobId, j -> j.failed("Adaptateur non trouvé: " + adapterPath));
                return;
            }

            log.info("Job {}: entraînement terminé, adaptateur: {}", jobId, adapterPath);

            // ── Étape 3 : Modèle prêt ──
            // Le fichier GGUF produit doit être placé dans ./data/models/ puis le serveur LLM redémarré.
            log.info("Job {}: modèle '{}' prêt → {}", jobId, request.modelName(), adapterPath);

            updateJob(jobId, j -> j.completed(adapterPath.toString()));

        } catch (Exception e) {
            log.error("Job {} échoué: {}", jobId, e.getMessage(), e);
            updateJob(jobId, j -> j.failed(e.getMessage()));
        }
    }

    /**
     * Exporte les paires avec un score de confiance >= seuil.
     */
    private Path exportFilteredDataset(Path dir, double minConfidence) throws Exception {
        List<TrainingPair> pairs = datasetGenerator.getAllPairs().stream()
                .filter(p -> p.metadata().confidence() >= minConfidence)
                .toList();

        Path file = dir.resolve("dataset.jsonl");
        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            for (TrainingPair pair : pairs) {
                writer.write(mapper.writeValueAsString(pair));
                writer.newLine();
            }
        }
        return file;
    }

    /**
     * Lance le script d'entraînement externe avec les paramètres LoRA.
     * Le script reçoit les arguments : dataset_path output_path base_model lora_rank lora_alpha epochs lr
     */
    private int runTrainingProcess(String jobId, FineTuningRequest request,
                                   Path datasetFile, Path adapterPath) throws Exception {
        List<String> command = List.of(
                trainingScript,
                datasetFile.toAbsolutePath().toString(),
                adapterPath.toAbsolutePath().toString(),
                request.baseModel(),
                String.valueOf(request.loraRank()),
                String.valueOf(request.loraAlpha()),
                String.valueOf(request.epochs()),
                String.valueOf(request.learningRate())
        );

        log.info("Job {}: commande = {}", jobId, String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true);

        Process process = pb.start();

        // Lire la sortie du process pour détecter la progression
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("Job {} [train]: {}", jobId, line);
                parseTrainingOutput(jobId, line);
            }
        }

        return process.waitFor();
    }

    /**
     * Parse les lignes de sortie du script pour mettre à jour la progression.
     * Format attendu : "epoch=2 loss=0.4523" ou "EPOCH 2/3 loss=0.4523"
     */
    private void parseTrainingOutput(String jobId, String line) {
        try {
            String lower = line.toLowerCase();
            if (lower.contains("epoch")) {
                Integer epoch = extractInt(lower, "epoch[= ]*");
                Double loss = extractDouble(lower, "loss[= ]*");
                if (epoch != null) {
                    updateJob(jobId, j -> j.withTrainingProgress(epoch, loss));
                }
            }
        } catch (Exception e) {
            // Parsing best-effort, on ne casse pas le process pour une ligne mal formatée
        }
    }

    private Integer extractInt(String text, String prefix) {
        var matcher = java.util.regex.Pattern.compile(prefix + "(\\d+)").matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private Double extractDouble(String text, String prefix) {
        var matcher = java.util.regex.Pattern.compile(prefix + "([\\d.]+)").matcher(text);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : null;
    }

    private int countLines(Path file) throws Exception {
        try (var lines = Files.lines(file)) {
            return (int) lines.count();
        }
    }

    private void updateJob(String jobId, java.util.function.UnaryOperator<FineTuningJob> updater) {
        jobs.computeIfPresent(jobId, (k, v) -> updater.apply(v));
    }
}
