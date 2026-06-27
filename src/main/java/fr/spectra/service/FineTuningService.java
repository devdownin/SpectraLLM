package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.dto.FineTuningJob;
import fr.spectra.dto.FineTuningJob.Status;
import fr.spectra.dto.FineTuningRequest;
import fr.spectra.model.TrainingPair;
import fr.spectra.model.DpoPair;
import fr.spectra.persistence.FineTuningJobEntity;
import fr.spectra.persistence.FineTuningJobRepository;
import fr.spectra.service.dataset.DatasetGeneratorService;
import fr.spectra.service.dataset.DpoGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.util.Comparator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final DpoGenerationService dpoGenerator;
    private final LlmChatClient llmClient;
    private final FineTuningJobRepository repository;
    private final Path workDir;
    private final String trainingScript;
    /**
     * Catégories/types exclus du SFT (jetons comparés à {@code category} ET {@code type}).
     * Levier « fine-tuning vs RAG » : le fine-tuning encode mal les faits volatils (événements,
     * nomenclatures qui changent) et vieillit ; mieux vaut les laisser au RAG et réserver le SFT
     * au style/format/procédure. Vide par défaut (aucune exclusion).
     */
    private final Set<String> sftExcludedCategories;

    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private final Set<String> cancelledJobs = ConcurrentHashMap.newKeySet();

    public FineTuningService(DatasetGeneratorService datasetGenerator,
                             DpoGenerationService dpoGenerator,
                             LlmChatClient llmClient,
                             FineTuningJobRepository repository,
                             @Value("${spectra.fine-tuning.work-dir:./data/fine-tuning}") String workDir,
                             @Value("${spectra.fine-tuning.script:./scripts/train.sh}") String trainingScript,
                             @Value("${spectra.fine-tuning.sft-excluded-categories:}") String sftExcludedCsv) {
         this.datasetGenerator = datasetGenerator;
         this.dpoGenerator = dpoGenerator;
         this.llmClient = llmClient;
         this.repository = repository;
         this.workDir = Path.of(workDir);
         // Chemin absolu : le process d'entraînement s'exécute avec workDir comme répertoire
         // courant, donc un chemin relatif ne serait pas résolu correctement.
         this.trainingScript = Path.of(trainingScript).toAbsolutePath().toString();
         this.sftExcludedCategories = parseCsvLower(sftExcludedCsv);
    }

    private static Set<String> parseCsvLower(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        Set<String> out = new java.util.HashSet<>();
        for (String token : csv.split(",")) {
            String t = token.strip().toLowerCase();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
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
                request.dpoEnabled(),
                request.orpoEnabled()
        );

        FineTuningJob job = FineTuningJob.pending(jobId, resolved);
        repository.save(FineTuningJobEntity.fromDto(job));
        runAsync(jobId, resolved);
        return jobId;
    }

    public FineTuningJob getJob(String jobId) {
        return repository.findById(jobId).map(FineTuningJobEntity::toDto).orElse(null);
    }

    public List<FineTuningJob> getAllJobs() {
        return repository.findAll().stream().map(FineTuningJobEntity::toDto).toList();
    }

    public boolean cancelJob(String jobId) {
        FineTuningJobEntity entity = repository.findById(jobId).orElse(null);
        if (entity == null) return false;
        FineTuningJob job = entity.toDto();
        if (job.status() == Status.COMPLETED || job.status() == Status.FAILED) return false;
        
        cancelledJobs.add(jobId);
        
        Process process = activeProcesses.remove(jobId);
        if (process != null) {
            log.info("Job {}: interruption forcée du processus OS d'entraînement.", jobId);
            process.destroyForcibly();
        }
        
        repository.save(FineTuningJobEntity.fromDto(job.failed("Annulé par l'utilisateur")));
        return true;
    }

    /**
     * Purge les jobs <b>échoués</b> de plus d'une heure et <b>supprime leur répertoire de travail</b>
     * (dataset + sorties partielles) pour éviter une fuite disque.
     *
     * <p>Les jobs {@code COMPLETED} sont conservés : leur répertoire contient l'adaptateur entraîné
     * (le modèle produit) et leur entrée en base porte le {@code outputPath} référencé ailleurs.
     * Les supprimer automatiquement détruirait le résultat du fine-tuning.</p>
     */
    @Scheduled(fixedDelay = 3_600_000)
    public void cleanupOldJobs() {
        Instant cutoff = Instant.now().minusSeconds(3600);
        List<FineTuningJobEntity> toDelete = repository.findAll().stream()
                .filter(e -> {
                    FineTuningJob j = e.toDto();
                    return j.status() == Status.FAILED
                            && j.completedAt() != null && j.completedAt().isBefore(cutoff);
                })
                .toList();
        for (FineTuningJobEntity e : toDelete) {
            deleteWorkDir(e.toDto().jobId());
        }
        repository.deleteAll(toDelete);
        cancelledJobs.removeIf(id -> repository.findById(id).isEmpty());
    }

    /** Supprime récursivement le répertoire de travail d'un job (best-effort). */
    private void deleteWorkDir(String jobId) {
        Path jobDir = workDir.resolve(jobId);
        if (!Files.exists(jobDir)) return;
        try (var paths = Files.walk(jobDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (Exception ex) { log.warn("Suppression impossible: {} ({})", p, ex.getMessage()); }
            });
        } catch (Exception ex) {
            log.warn("Nettoyage du répertoire {} impossible: {}", jobDir, ex.getMessage());
        }
    }

    @Async
    protected void runAsync(String jobId, FineTuningRequest request) {
        try {
            // ── Étape 1 : Export du dataset filtré ──
            updateJob(jobId, j -> j.withStatus(Status.EXPORTING_DATASET, "Export du dataset..."));

            Path jobDir = workDir.resolve(jobId);
            Files.createDirectories(jobDir);

            // DPO comme ORPO consomment le même dataset de préférence {prompt, chosen, rejected}.
            boolean preference = request.dpoEnabled() || request.orpoEnabled();

            Path datasetFile = preference
                    ? exportDpoDataset(jobDir)
                    : exportFilteredDataset(jobDir, request.minConfidence());
            int datasetSize = countLines(datasetFile);
            updateJob(jobId, j -> j.withDatasetSize(datasetSize));

            if (datasetSize == 0) {
                String reason = preference
                        ? "Aucune paire de préférence disponible — lancez d'abord POST /api/dataset/dpo/generate"
                        : "Dataset vide après filtrage (minConfidence=" + request.minConfidence() + ")";
                updateJob(jobId, j -> j.failed(reason));
                return;
            }

            log.info("Job {}: dataset exporté ({} {})", jobId, datasetSize,
                    preference ? "paires de préférence" : "paires SFT");

            // ── Étape 2 : Lancement de l'entraînement externe ──
            updateJob(jobId, j -> j.withStatus(Status.TRAINING, "Lancement de l'entraînement..."));

            // train_host.py produit un répertoire d'adaptateur LoRA (format HuggingFace/PEFT),
            // pas un fichier .gguf — la conversion GGUF est une étape distincte (export_gguf.py).
            Path adapterPath = jobDir.resolve("adapter");

            int exitCode = runTrainingProcess(jobId, request, datasetFile, adapterPath);

            if (cancelledJobs.contains(jobId)) {
                log.info("Job {} annulé par l'utilisateur, arrêt de la tâche asynchrone.", jobId);
                return;
            }

            if (exitCode != 0) {
                updateJob(jobId, j -> j.failed("Le script d'entraînement a retourné le code " + exitCode));
                return;
            }

            // L'artefact d'un entraînement PEFT réussi est adapter_config.json dans le dossier.
            if (!Files.exists(adapterPath.resolve("adapter_config.json"))) {
                updateJob(jobId, j -> j.failed("Adaptateur non trouvé: " + adapterPath.resolve("adapter_config.json")));
                return;
            }

            log.info("Job {}: entraînement terminé, adaptateur: {}", jobId, adapterPath);

            // ── Étape 3 : Modèle prêt ──
            log.info("Job {}: modèle '{}' prêt → {}", jobId, request.modelName(), adapterPath);

            updateJob(jobId, j -> j.completed(adapterPath.toString()));

        } catch (Exception e) {
            if (cancelledJobs.contains(jobId)) {
                log.info("Job {} annulé par l'utilisateur (interruption de flux), arrêt propre.", jobId);
                return;
            }
            log.error("Job {} échoué: {}", jobId, e.getMessage(), e);
            updateJob(jobId, j -> j.failed(e.getMessage()));
        } finally {
            cancelledJobs.remove(jobId);
        }
    }

    /**
     * Exporte les paires avec un score de confiance >= seuil.
     */
    private Path exportFilteredDataset(Path dir, double minConfidence) throws Exception {
        List<TrainingPair> pairs = datasetGenerator.getAllPairs().stream()
                .filter(p -> p.metadata().confidence() >= minConfidence)
                .filter(p -> !isExcludedFromSft(p))
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

    /** Vrai si la paire relève d'une catégorie/type exclu du SFT (laissé au RAG). */
    private boolean isExcludedFromSft(TrainingPair p) {
        if (sftExcludedCategories.isEmpty()) return false;
        String category = p.metadata().category();
        String type = p.metadata().type();
        return (category != null && sftExcludedCategories.contains(category.toLowerCase()))
                || (type != null && sftExcludedCategories.contains(type.toLowerCase()));
    }

    /**
     * Exporte les paires DPO ({@code {prompt, chosen, rejected}}) pour l'entraînement DPO.
     * Sans cet export, le mode DPO recevait à tort le dataset SFT au format {@code conversations}.
     */
    private Path exportDpoDataset(Path dir) throws Exception {
        List<DpoPair> pairs = dpoGenerator.getAllPairs();

        Path file = dir.resolve("dataset.jsonl");
        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            for (DpoPair pair : pairs) {
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
                String.valueOf(request.learningRate()),
                String.valueOf(request.packingEnabled()),
                String.valueOf(request.dpoEnabled()),
                String.valueOf(request.orpoEnabled())
        );

        log.info("Job {}: commande = {}", jobId, String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true);

        Process process = pb.start();
        activeProcesses.put(jobId, process);

        try {
            // Lire la sortie du process pour détecter la progression
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelledJobs.contains(jobId)) {
                        process.destroyForcibly();
                        break;
                    }
                    log.info("Job {} [train]: {}", jobId, line);
                    parseTrainingOutput(jobId, line);
                }
            }
            return process.waitFor();
        } finally {
            activeProcesses.remove(jobId);
        }
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
        repository.findById(jobId).ifPresent(entity -> {
            FineTuningJob updated = updater.apply(entity.toDto());
            repository.save(FineTuningJobEntity.fromDto(updated));
        });
    }
}
