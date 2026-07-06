package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.dto.FineTuningJob;
import fr.spectra.dto.FineTuningJob.Status;
import fr.spectra.dto.FineTuningRequest;
import fr.spectra.model.AssistantPersona;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
    private final FineTuningJobRepository repository;
    private final TrainingLogBroadcaster broadcaster;
    private final ModelRegistryService modelRegistry;
    private final BaseModelCatalog baseModelCatalog;
    /** Alias (ou repo HF) utilisé quand la requête ne précise pas de modèle de base. */
    private final String defaultBaseModel;
    private final Path workDir;
    private final String trainingScript;
    /** Script de fusion LoRA + conversion GGUF (voir scripts/export_gguf.py). */
    private final String exportScript;
    /** Interpréteur Python utilisé pour l'export GGUF. */
    private final String pythonBin;
    /** Volume partagé des modèles GGUF servis par llm-chat (cf. LlmFitService). */
    private final String modelsDir;
    /**
     * Catégories/types exclus du SFT (jetons comparés à {@code category} ET {@code type}).
     * Levier « fine-tuning vs RAG » : le fine-tuning encode mal les faits volatils (événements,
     * nomenclatures qui changent) et vieillit ; mieux vaut les laisser au RAG et réserver le SFT
     * au style/format/procédure. Vide par défaut (aucune exclusion).
     */
    private final Set<String> sftExcludedCategories;

    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private final Set<String> cancelledJobs = ConcurrentHashMap.newKeySet();
    /** Un seul entraînement à la fois : lancer plusieurs train_host.py en parallèle sature CPU/RAM. */
    private final AtomicBoolean trainingRunning = new AtomicBoolean(false);

    // Auto-injection pour invoquer runAsync via le proxy Spring : sans cela, l'appel interne
    // court-circuite l'AOP et @Async est ignoré → l'entraînement s'exécuterait de façon
    // SYNCHRONE en bloquant le thread HTTP de la requête pendant toute sa durée.
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private FineTuningService self;

    public FineTuningService(DatasetGeneratorService datasetGenerator,
                             DpoGenerationService dpoGenerator,
                             FineTuningJobRepository repository,
                             TrainingLogBroadcaster broadcaster,
                             ModelRegistryService modelRegistry,
                             BaseModelCatalog baseModelCatalog,
                             @Value("${spectra.fine-tuning.default-base-model:phi3}") String defaultBaseModel,
                             @Value("${spectra.fine-tuning.work-dir:./data/fine-tuning}") String workDir,
                             @Value("${spectra.fine-tuning.script:./scripts/train.sh}") String trainingScript,
                             @Value("${spectra.fine-tuning.export-script:./scripts/export_gguf.py}") String exportScript,
                             @Value("${spectra.fine-tuning.python:python3}") String pythonBin,
                             @Value("${llmfit.models-dir:./data/models}") String modelsDir,
                             @Value("${spectra.fine-tuning.sft-excluded-categories:}") String sftExcludedCsv) {
         this.datasetGenerator = datasetGenerator;
         this.dpoGenerator = dpoGenerator;
         this.repository = repository;
         this.broadcaster = broadcaster;
         this.modelRegistry = modelRegistry;
         this.baseModelCatalog = baseModelCatalog;
         this.defaultBaseModel = defaultBaseModel;
         this.workDir = Path.of(workDir);
         // Chemin absolu : le process d'entraînement s'exécute avec workDir comme répertoire
         // courant, donc un chemin relatif ne serait pas résolu correctement.
         this.trainingScript = Path.of(trainingScript).toAbsolutePath().toString();
         this.exportScript = Path.of(exportScript).toAbsolutePath().toString();
         this.pythonBin = pythonBin;
         this.modelsDir = modelsDir;
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
     * Au démarrage, tout job resté non-terminal (PENDING/EXPORTING/TRAINING/IMPORTING) est
     * orphelin : son process OS a disparu avec l'ancienne JVM. On le marque FAILED pour ne pas
     * laisser le suivi tourner indéfiniment côté UI.
     */
    @jakarta.annotation.PostConstruct
    void reconcileInterruptedJobs() {
        try {
            for (FineTuningJobEntity e : repository.findAll()) {
                FineTuningJob j = e.toDto();
                if (j.status() != Status.COMPLETED && j.status() != Status.FAILED) {
                    repository.save(FineTuningJobEntity.fromDto(
                            j.failed("Interrompu par un redémarrage du serveur")));
                    log.warn("Job {} ({}) marqué FAILED : interrompu par un redémarrage", j.jobId(), j.status());
                }
            }
        } catch (Exception ex) {
            log.warn("Réconciliation des jobs interrompus impossible: {}", ex.getMessage());
        }
    }

    /**
     * Lance un job de fine-tuning asynchrone.
     * @return l'identifiant du job, ou {@code null} si un entraînement est déjà en cours (409).
     */
    public String submit(FineTuningRequest request) {
        // Un seul entraînement simultané : refuser tant qu'un job tourne.
        if (!trainingRunning.compareAndSet(false, true)) {
            return null;
        }
        try {
            String jobId = UUID.randomUUID().toString();

            // Modèle de base : alias du catalogue (base_models.json) ou repo HF complet.
            // La résolution est vérifiée ICI (fail-fast, HTTP 400) plutôt que d'échouer des
            // minutes plus tard au téléchargement HuggingFace. NB : l'ancien défaut — le
            // modèle ACTIF du registre — était voué à l'échec (un GGUF servi n'a pas de
            // poids entraînables) ; le défaut est désormais un alias entraînable configurable.
            String baseModel = request.baseModel() != null && !request.baseModel().isBlank()
                    ? request.baseModel()
                    : defaultBaseModel;
            baseModelCatalog.resolveHfRepo(baseModel);

            FineTuningRequest resolved = new FineTuningRequest(
                    request.modelName(),
                    baseModel,
                    request.loraRank(),
                    request.loraAlpha(),
                    request.epochs(),
                    request.learningRate(),
                    request.minConfidence(),
                    request.packingEnabled(),
                    request.dpoEnabled(),
                    request.orpoEnabled(),
                    request.exportGguf()
            );

            FineTuningJob job = FineTuningJob.pending(jobId, resolved);
            repository.save(FineTuningJobEntity.fromDto(job));
            // Passer par le proxy pour que @Async prenne effet (fallback `this` hors Spring).
            (self != null ? self : this).runAsync(jobId, resolved);
            // runAsync (@Async) libère trainingRunning dans son bloc finally.
            return jobId;
        } catch (RuntimeException ex) {
            // Échec avant la prise en charge asynchrone : libérer le verrou pour ne pas
            // bloquer définitivement tout entraînement futur.
            trainingRunning.set(false);
            throw ex;
        }
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
            broadcaster.info("Job " + jobId + " : export du dataset…");
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
            broadcaster.info("Job " + jobId + " : lancement de l'entraînement (" + datasetSize + " exemples)…");
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

            if (Boolean.TRUE.equals(request.exportGguf())) {
                // Étape 3b (opt-in) : fusion LoRA → GGUF → enregistrement pour déploiement.
                exportGgufAndRegister(jobId, request, adapterPath, jobDir);
            } else {
                broadcaster.info("Job " + jobId + " : adaptateur entraîné → " + adapterPath
                        + " (exporter en GGUF puis enregistrer pour le déployer)");
                updateJob(jobId, j -> j.completed(adapterPath.toString()));
            }

        } catch (Exception e) {
            if (cancelledJobs.contains(jobId)) {
                log.info("Job {} annulé par l'utilisateur (interruption de flux), arrêt propre.", jobId);
                return;
            }
            log.error("Job {} échoué: {}", jobId, e.getMessage(), e);
            broadcaster.error("Job " + jobId + " échoué : " + e.getMessage());
            updateJob(jobId, j -> j.failed(e.getMessage()));
        } finally {
            cancelledJobs.remove(jobId);
            trainingRunning.set(false);
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
        // Repo HF résolu depuis le manifeste unique (base_models.json) : le script reçoit
        // toujours un identifiant directement téléchargeable, jamais un alias ambigu.
        String baseHfRepo = baseModelCatalog.resolveHfRepo(request.baseModel());
        List<String> command = List.of(
                trainingScript,
                datasetFile.toAbsolutePath().toString(),
                adapterPath.toAbsolutePath().toString(),
                baseHfRepo,
                String.valueOf(request.loraRank()),
                String.valueOf(request.loraAlpha()),
                String.valueOf(request.epochs()),
                String.valueOf(request.learningRate()),
                String.valueOf(request.packingEnabled()),
                String.valueOf(request.dpoEnabled()),
                String.valueOf(request.orpoEnabled())
        );
        return runProcess(jobId, "train", command, line -> parseTrainingOutput(jobId, line));
    }

    /**
     * Lance un sous-processus, diffuse sa sortie ligne à ligne (logs + SSE) et respecte
     * l'annulation ({@link #cancelledJobs}). L'enregistrement dans {@link #activeProcesses}
     * permet à {@code cancelJob} de tuer le process en cours.
     *
     * @param onLine traitement optionnel par ligne (ex. extraction de la progression)
     * @return le code de sortie du process
     */
    private int runProcess(String jobId, String label, List<String> command, Consumer<String> onLine)
            throws Exception {
        log.info("Job {}: commande = {}", jobId, String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true);

        Process process = pb.start();
        activeProcesses.put(jobId, process);

        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelledJobs.contains(jobId)) {
                        process.destroyForcibly();
                        break;
                    }
                    log.info("Job {} [{}]: {}", jobId, label, line);
                    broadcaster.info(line); // diffusion temps réel vers /api/sse/training-logs
                    if (onLine != null) onLine.accept(line);
                }
            }
            return process.waitFor();
        } finally {
            activeProcesses.remove(jobId);
        }
    }

    /**
     * Fusionne l'adaptateur LoRA, le convertit en GGUF via {@code export_gguf.py}, copie le
     * fichier dans le volume partagé des modèles puis l'enregistre — le modèle fine-tuné devient
     * ainsi déployable/servable par llm-chat sans étape manuelle. En cas d'échec, le job passe
     * FAILED mais l'adaptateur entraîné reste conservé sur disque.
     */
    private void exportGgufAndRegister(String jobId, FineTuningRequest request,
                                       Path adapterPath, Path jobDir) throws Exception {
        updateJob(jobId, j -> j.withStatus(Status.IMPORTING_MODEL, "Fusion LoRA + conversion GGUF…"));
        broadcaster.info("Job " + jobId + " : fusion de l'adaptateur, conversion GGUF et enregistrement…");

        Path mergedDir = jobDir.resolve("merged");
        // Même résolution que l'entraînement (manifeste unique) : l'adaptateur LoRA n'est
        // fusionnable QUE sur le modèle de base exact qui l'a entraîné.
        String baseHfRepo = baseModelCatalog.resolveHfRepo(request.baseModel());
        List<String> command = new java.util.ArrayList<>(List.of(
                pythonBin, exportScript,
                "--adapter", adapterPath.toAbsolutePath().toString(),
                "--output", mergedDir.toAbsolutePath().toString(),
                "--model-name", request.modelName(),
                "--base-model", baseHfRepo));

        int exitCode = runProcess(jobId, "export", command, null);

        if (cancelledJobs.contains(jobId)) {
            log.info("Job {} annulé pendant l'export GGUF.", jobId);
            return;
        }
        if (exitCode != 0) {
            throw new IllegalStateException("La conversion GGUF a retourné le code " + exitCode
                    + " (adaptateur conservé : " + adapterPath + ")");
        }

        Path gguf = mergedDir.resolve("model.gguf");
        if (!Files.exists(gguf)) {
            throw new IllegalStateException("Fichier GGUF introuvable après conversion: " + gguf);
        }

        // Copie dans le volume partagé pour que llm-chat (monté sur modelsDir) puisse le servir.
        Path modelsDirPath = Path.of(modelsDir);
        Files.createDirectories(modelsDirPath);
        Path target = modelsDirPath.resolve(safeFileName(request.modelName()) + ".gguf");
        Files.copy(gguf, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        String registeredPath = target.toAbsolutePath().toString();

        // La persona d'enregistrement DOIT correspondre à celle de l'entraînement, sinon le
        // fine-tuning est dégradé au service (cf. AssistantPersona.SYSTEM_PROMPT).
        modelRegistry.registerChatModel(
                request.modelName(),
                registeredPath,
                AssistantPersona.SYSTEM_PROMPT,
                Map.of("jobId", jobId, "baseModel", String.valueOf(request.baseModel())),
                "fine-tuning",
                new ModelRegistryService.ModelOrigin(
                        baseHfRepo,
                        "q8_0", // quantisation appliquée par export_gguf.py (convert --outtype q8_0)
                        baseModelCatalog.find(request.baseModel())
                                .map(BaseModelCatalog.BaseModel::contextLength)
                                .orElse(null)));

        log.info("Job {}: modèle '{}' converti et enregistré → {}", jobId, request.modelName(), registeredPath);
        broadcaster.info("Job " + jobId + " : modèle '" + request.modelName()
                + "' enregistré et déployable → " + registeredPath);
        updateJob(jobId, j -> j.completed(registeredPath));
    }

    /** Neutralise un nom de modèle pour l'utiliser comme nom de fichier GGUF. */
    private static String safeFileName(String modelName) {
        String cleaned = modelName.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-+", "-");
        cleaned = cleaned.replaceAll("^-+|-+$", "");
        return cleaned.isEmpty() ? "model" : cleaned;
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
        // Ne jamais ressusciter un job annulé/terminal : une ligne de progression tardive
        // émise par le thread lecteur ne doit pas réécrire le statut TRAINING par-dessus le
        // FAILED posé par cancelJob (cancelledJobs.add() est effectué AVANT ce save).
        if (cancelledJobs.contains(jobId)) return;
        repository.findById(jobId).ifPresent(entity -> {
            FineTuningJob current = entity.toDto();
            if (current.status() == Status.FAILED || current.status() == Status.COMPLETED) return;
            FineTuningJob updated = updater.apply(current);
            repository.save(FineTuningJobEntity.fromDto(updated));
        });
    }
}
