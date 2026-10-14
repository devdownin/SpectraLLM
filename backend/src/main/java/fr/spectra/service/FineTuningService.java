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
import fr.spectra.service.training.ExportSpec;
import fr.spectra.service.training.TrainingRunner;
import fr.spectra.service.training.TrainingSpec;
import fr.spectra.service.training.TrainingUnavailableException;
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
    /** Trace persistante : ce que l'UI relit après un rechargement (S1, S8). */
    private final JobTelemetryStore telemetryStore;
    private final ModelRegistryService modelRegistry;
    /**
     * Évaluation enchaînée après l'enregistrement du modèle (F14 côté qualité, cf.
     * {@link FineTuningRequest#autoEvaluate()}). Injectée en {@code @Lazy} : les deux services
     * partagent {@link DatasetGeneratorService} et Spring refuserait un cycle au démarrage.
     */
    private final EvaluationService evaluationService;
    private final BaseModelCatalog baseModelCatalog;
    private final GedService gedService;
    private final fr.spectra.persistence.IngestedFileRepository ingestedFileRepository;
    /** Alias (ou repo HF) utilisé quand la requête ne précise pas de modèle de base. */
    private final String defaultBaseModel;
    private final Path workDir;
    /**
     * Exécuteur de l'entraînement. Ce service ne connaît plus ni {@code python3}, ni
     * {@code train.sh}, ni l'ordre des arguments positionnels : il décrit le travail
     * ({@link TrainingSpec}) et demande s'il est exécutable (F1, F10).
     */
    private final TrainingRunner trainingRunner;
    /** Volume partagé des modèles GGUF servis par llm-chat (cf. LlmFitService). */
    private final String modelsDir;
    /**
     * Catégories/types exclus du SFT (jetons comparés à {@code category} ET {@code type}).
     * Levier « fine-tuning vs RAG » : le fine-tuning encode mal les faits volatils (événements,
     * nomenclatures qui changent) et vieillit ; mieux vaut les laisser au RAG et réserver le SFT
     * au style/format/procédure. Vide par défaut (aucune exclusion).
     */
    private final Set<String> sftExcludedCategories;

    /**
     * Jobs annulés. Reste ici — et non dans le runner — parce que l'annulation a plusieurs
     * lecteurs (statuts, export, post-traitement) mais un seul propriétaire : le cycle de vie.
     */
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
                             JobTelemetryStore telemetryStore,
                             ModelRegistryService modelRegistry,
                             @org.springframework.context.annotation.Lazy EvaluationService evaluationService,
                             BaseModelCatalog baseModelCatalog,
                             GedService gedService,
                             fr.spectra.persistence.IngestedFileRepository ingestedFileRepository,
                             @Value("${spectra.fine-tuning.default-base-model:phi3}") String defaultBaseModel,
                             TrainingRunner trainingRunner,
                             @Value("${spectra.fine-tuning.work-dir:./data/fine-tuning}") String workDir,
                             @Value("${llmfit.models-dir:./data/models}") String modelsDir,
                             @Value("${spectra.fine-tuning.sft-excluded-categories:}") String sftExcludedCsv) {
         this.datasetGenerator = datasetGenerator;
         this.dpoGenerator = dpoGenerator;
         this.repository = repository;
         this.broadcaster = broadcaster;
         this.telemetryStore = telemetryStore;
         this.modelRegistry = modelRegistry;
         this.evaluationService = evaluationService;
         this.baseModelCatalog = baseModelCatalog;
         this.gedService = gedService;
         this.ingestedFileRepository = ingestedFileRepository;
         this.defaultBaseModel = defaultBaseModel;
         this.workDir = Path.of(workDir);
         this.trainingRunner = trainingRunner;
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
     * orphelin : l'exécution qui le portait a disparu avec l'ancienne JVM. On le marque FAILED
     * pour ne pas laisser le suivi tourner indéfiniment côté UI.
     *
     * <p><b>L'annulation auprès du runner n'est pas redondante</b>, et c'est le mode conteneur
     * qui l'impose. En mode hôte, le sous-processus meurt avec la JVM : marquer FAILED suffit.
     * En mode HTTP, l'entraînement vit dans un <i>autre</i> conteneur, qui n'apprend le départ
     * de son client qu'en tentant d'écrire sur la connexion fermée — c'est-à-dire à la prochaine
     * ligne de journal. Un entraînement silencieux pendant une heure resterait donc à consommer
     * CPU, RAM et GPU pour un job que l'API a déjà déclaré perdu.
     *
     * <p>L'appel est sans effet en mode hôte (aucun processus enregistré dans une JVM neuve) et
     * tolérant à l'échec (le trainer peut être injoignable) : il ne coûte rien là où il ne sert
     * pas.
     */
    @jakarta.annotation.PostConstruct
    void reconcileInterruptedJobs() {
        try {
            for (FineTuningJobEntity e : repository.findAll()) {
                FineTuningJob j = e.toDto();
                if (!j.status().isTerminal()) {
                    repository.save(FineTuningJobEntity.fromDto(
                            j.failed("Interrompu par un redémarrage du serveur")));
                    log.warn("Job {} ({}) marqué FAILED : interrompu par un redémarrage", j.jobId(), j.status());
                    // try/catch RESSERRÉ sur l'annulation, et non délégué à celui de la boucle :
                    // un trainer injoignable y ferait sinon avorter le traitement des jobs
                    // suivants, qui resteraient TRAINING à jamais — le fantôme même que cette
                    // méthode élimine. L'annulation est accessoire ; le marquage ne l'est pas.
                    try {
                        if (trainingRunner.cancel(j.jobId())) {
                            log.warn("Job {} : une exécution survivait au redémarrage — interrompue.",
                                    j.jobId());
                        }
                    } catch (Exception cancelFailure) {
                        log.warn("Job {} : annulation non transmise au runner — {}",
                                j.jobId(), cancelFailure.getMessage());
                    }
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
        // F1 — la disponibilité de l'exécuteur se vérifie AVANT d'accepter le travail, et avant
        // de prendre le verrou. Auparavant le job était accepté, son jeu de données généré, son
        // répertoire créé, puis tout mourait sur une IOException parce que l'image ne contient
        // ni Python ni scripts/. Un refus immédiat et motivé vaut mieux qu'un échec à mi-course.
        if (!trainingRunner.isAvailable()) {
            throw new TrainingUnavailableException(trainingRunner.unavailabilityReason());
        }
        // Même logique de refus immédiat : une évaluation demandée sans export GGUF ne pourra
        // pas avoir lieu — un adaptateur LoRA seul n'est pas servable par llm-chat. Le découvrir
        // après des heures d'entraînement, ou pire l'exécuter quand même en interrogeant le
        // modèle ACTIF sous le nom du nouveau, seraient l'un et l'autre pires que ce refus.
        if (Boolean.TRUE.equals(request.autoEvaluate()) && !Boolean.TRUE.equals(request.exportGguf())) {
            throw new IllegalArgumentException(
                    "L'évaluation automatique exige l'export GGUF : sans enregistrement, le modèle "
                            + "entraîné n'est pas servable et ne peut donc pas être évalué.");
        }
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
                    request.exportGguf(),
                    request.valSplit(),
                    request.autoEvaluate()
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
        if (job.status().isTerminal()) return false;
        
        cancelledJobs.add(jobId);
        
        if (trainingRunner.cancel(jobId)) {
            log.info("Job {}: interruption forcée de l'exécution en cours.", jobId);
        }
        
        repository.save(FineTuningJobEntity.fromDto(job.cancelled("Arrêté à la demande de l'utilisateur")));
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
                    return (j.status() == Status.FAILED || j.status() == Status.CANCELLED)
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
            publishAndRecord(jobId, "INFO", "Job " + jobId + " : export du dataset…");
            updateJob(jobId, j -> j.withStatus(Status.EXPORTING_DATASET, "Export du dataset..."));

            Path jobDir = workDir.resolve(jobId);
            Files.createDirectories(jobDir);

            // DPO comme ORPO consomment le même dataset de préférence {prompt, chosen, rejected}.
            boolean preference = request.dpoEnabled() || request.orpoEnabled();

            ExportedDataset dataset = preference
                    ? exportDpoDataset(jobDir)
                    : exportFilteredDataset(jobDir, request.minConfidence());
            Path datasetFile = dataset.file();
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
            // Le flux restait muet de bout en bout de cette phase : l'utilisateur ne savait ni
            // qu'elle avançait, ni ce qu'elle avait produit.
            publishAndRecord(jobId, "INFO", "Job " + jobId + " : dataset exporté — " + datasetSize
                    + (preference ? " paires de préférence" : " paires SFT"));

            // ── Étape 2 : Lancement de l'entraînement externe ──
            publishAndRecord(jobId, "INFO", "Job " + jobId + " : lancement de l'entraînement ("
                    + datasetSize + " exemples)…");
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
                publishAndRecord(jobId, "INFO", "Job " + jobId + " : adaptateur entraîné → " + adapterPath
                        + " (exporter en GGUF puis enregistrer pour le déployer)");
                updateJob(jobId, j -> j.completed(adapterPath.toString()));
            }

            // ── Étape 4 : traçabilité GED — uniquement si le job a réellement abouti
            // (l'export GGUF peut avoir été annulé sans passer COMPLETED).
            boolean completedOk = repository.findById(jobId)
                    .map(e -> e.toDto().status() == Status.COMPLETED)
                    .orElse(false);
            if (completedOk) {
                markDocumentsTrained(jobId, request.modelName(), dataset.sources());
            }

        } catch (Exception e) {
            if (cancelledJobs.contains(jobId)) {
                log.info("Job {} annulé par l'utilisateur (interruption de flux), arrêt propre.", jobId);
                return;
            }
            log.error("Job {} échoué: {}", jobId, e.getMessage(), e);
            publishAndRecord(jobId, "ERROR", "Job " + jobId + " échoué : " + e.getMessage());
            updateJob(jobId, j -> j.failed(e.getMessage()));
        } finally {
            cancelledJobs.remove(jobId);
            trainingRunning.set(false);
        }
    }

    /**
     * Dataset exporté sur disque + provenance : les {@code sources} sont les noms de
     * documents GED ({@code sourceFile} des chunks) dont les paires sont issues — base de
     * la traçabilité R1/R2 en fin d'entraînement ({@link #markDocumentsTrained}).
     */
    private record ExportedDataset(Path file, Set<String> sources) {}

    /**
     * Exporte les paires avec un score de confiance >= seuil.
     */
    private ExportedDataset exportFilteredDataset(Path dir, double minConfidence) throws Exception {
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
        Set<String> sources = pairs.stream()
                .map(p -> p.metadata() != null ? p.metadata().source() : null)
                .filter(FineTuningService::isTraceableSource)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return new ExportedDataset(file, sources);
    }

    /**
     * Vrai si la paire relève d'une catégorie/type exclu du SFT (laissé au RAG).
     *
     * <p>La catégorie du <b>document source</b> (R8) est prise en compte au même titre que
     * celle de la paire : c'est ce qui permet d'écarter tout un pan thématique du corpus
     * — « n'entraîne pas sur le contractuel » — sans avoir à raisonner sur la nature de
     * chaque paire produite.</p>
     */
    private boolean isExcludedFromSft(TrainingPair p) {
        if (sftExcludedCategories.isEmpty()) return false;
        return isExcluded(p.metadata().category())
                || isExcluded(p.metadata().type())
                || isExcluded(p.metadata().documentCategory());
    }

    private boolean isExcluded(String value) {
        return value != null && sftExcludedCategories.contains(value.toLowerCase());
    }

    /**
     * Exporte les paires DPO pour l'entraînement DPO/ORPO au <b>format conversationnel</b> de
     * TRL : {@code prompt}, {@code chosen} et {@code rejected} sont des <i>listes de messages</i>
     * {@code {role, content}}.
     *
     * <p>C'est ce format — et lui seul — qui fait appliquer par TRL le gabarit de conversation du
     * modèle de base, donc <b>la même mise en forme que le SFT et que le service</b>. L'export
     * précédent sérialisait le {@code DpoPair} tel quel : un {@code prompt} en texte brut
     * (persona et question concaténées, sans aucun marqueur de rôle) auquel TRL n'applique aucun
     * gabarit. La phase DPO optimisait ainsi sur une troisième convention, ni celle apprise en
     * SFT ni celle servie en production.</p>
     */
    private ExportedDataset exportDpoDataset(Path dir) throws Exception {
        List<DpoPair> pairs = dpoGenerator.getAllPairs();

        Path file = dir.resolve("dataset.jsonl");
        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            for (DpoPair pair : pairs) {
                writer.write(mapper.writeValueAsString(toConversationalPair(pair)));
                writer.newLine();
            }
        }
        Set<String> sources = pairs.stream()
                .map(DpoPair::source)
                .filter(FineTuningService::isTraceableSource)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return new ExportedDataset(file, sources);
    }

    /**
     * Convertit une paire de préférence en format conversationnel TRL.
     *
     * <p>Le {@code prompt} est reconstruit comme la conversation réellement servie : la persona
     * en message système (même constante qu'en SFT et qu'au service — cf. {@link AssistantPersona})
     * puis la question en message utilisateur. Les paires historiques dont le {@code prompt}
     * embarque déjà la persona concaténée sont normalisées, sinon celle-ci apparaîtrait deux
     * fois.</p>
     *
     * <p>Seules les trois clés attendues par TRL sont émises : les métadonnées
     * ({@code category}, {@code source}) ne servent qu'à la traçabilité côté Spectra.</p>
     */
    static Map<String, Object> toConversationalPair(DpoPair pair) {
        return Map.of(
                "prompt", List.of(
                        Map.of("role", "system", "content", AssistantPersona.SYSTEM_PROMPT),
                        Map.of("role", "user", "content", stripPersonaPrefix(pair.prompt()))),
                "chosen", List.of(Map.of("role", "assistant", "content", pair.chosen())),
                "rejected", List.of(Map.of("role", "assistant", "content", pair.rejected())));
    }

    /** Retire la persona d'un prompt historique où elle était concaténée à la question. */
    private static String stripPersonaPrefix(String prompt) {
        if (prompt == null) return "";
        String trimmed = prompt.strip();
        if (trimmed.startsWith(AssistantPersona.SYSTEM_PROMPT)) {
            return trimmed.substring(AssistantPersona.SYSTEM_PROMPT.length()).strip();
        }
        return trimmed;
    }

    /** Vrai si la provenance d'une paire pointe un document GED identifiable. */
    private static boolean isTraceableSource(String source) {
        return source != null && !source.isBlank() && !"inconnu".equals(source);
    }

    /**
     * Traçabilité GED de fin d'entraînement : chaque document dont des paires ont nourri le
     * dataset est lié au modèle ({@code TRAINED_ON}) et son cycle de vie avance vers TRAINED
     * en respectant la machine à états (INGESTED → QUALIFIED → TRAINED ; un document déjà
     * TRAINED ou ARCHIVED n'est pas touché). Ces liens n'étaient auparavant posés que
     * manuellement via l'API — le statut TRAINED était en pratique inatteignable.
     * Best-effort : un échec de traçabilité ne remet jamais en cause le job COMPLETED.
     * Package-visible pour les tests.
     */
    void markDocumentsTrained(String jobId, String modelName, Set<String> sources) {
        if (modelName == null || modelName.isBlank() || sources.isEmpty()) return;
        int traced = 0;
        for (String source : sources) {
            List<fr.spectra.persistence.IngestedFileEntity> docs;
            try {
                docs = ingestedFileRepository.findByFileName(source);
            } catch (Exception e) {
                log.warn("Job {}: documents GED illisibles pour la source '{}' : {}", jobId, source, e.getMessage());
                continue;
            }
            for (fr.spectra.persistence.IngestedFileEntity doc : docs) {
                try {
                    gedService.linkToModel(doc.getSha256(), modelName,
                            fr.spectra.persistence.DocumentModelLinkEntity.LinkType.TRAINED_ON, "fine-tuning");
                    advanceToTrained(doc);
                    traced++;
                } catch (Exception e) {
                    log.warn("Job {}: traçabilité GED impossible pour '{}' ({}) : {}",
                            jobId, source, doc.getSha256(), e.getMessage());
                }
            }
        }
        if (traced > 0) {
            log.info("Job {}: {} document(s) GED lié(s) au modèle '{}' et passé(s) TRAINED", jobId, traced, modelName);
            publishAndRecord(jobId, "INFO", "Job " + jobId + " : " + traced
                    + " document(s) source tracé(s) en GED (TRAINED)");
        }
    }

    /** Avance le cycle de vie vers TRAINED selon les transitions autorisées. */
    private void advanceToTrained(fr.spectra.persistence.IngestedFileEntity doc) {
        switch (doc.getLifecycle()) {
            case QUALIFIED -> gedService.transitionLifecycle(doc.getSha256(),
                    fr.spectra.persistence.IngestedFileEntity.Lifecycle.TRAINED, "fine-tuning");
            case INGESTED -> {
                gedService.transitionLifecycle(doc.getSha256(),
                        fr.spectra.persistence.IngestedFileEntity.Lifecycle.QUALIFIED, "fine-tuning");
                gedService.transitionLifecycle(doc.getSha256(),
                        fr.spectra.persistence.IngestedFileEntity.Lifecycle.TRAINED, "fine-tuning");
            }
            default -> { /* TRAINED déjà atteint, ou ARCHIVED : ne pas ressusciter */ }
        }
    }

    /**
     * Décrit l'entraînement à mener et le confie au {@link TrainingRunner}.
     *
     * <p>Ce service ne construit plus de ligne de commande : la traduction du
     * {@link TrainingSpec} en arguments — et l'ordre de ceux-ci, seul endroit où il compte
     * encore — appartient au runner, qui seul connaît ce qu'il invoque.
     */
    private int runTrainingProcess(String jobId, FineTuningRequest request,
                                   Path datasetFile, Path adapterPath) throws Exception {
        // Repo HF résolu depuis le manifeste unique (base_models.json) : le runner reçoit
        // toujours un identifiant directement téléchargeable, jamais un alias ambigu.
        TrainingSpec spec = new TrainingSpec(
                jobId, datasetFile, adapterPath,
                baseModelCatalog.resolveHfRepo(request.baseModel()),
                request.loraRank(), request.loraAlpha(), request.epochs(),
                request.learningRate(), request.packingEnabled(),
                request.dpoEnabled(), request.orpoEnabled(), request.valSplit());

        return trainingRunner.train(spec, line -> onProcessLine(jobId, "train", line),
                () -> cancelledJobs.contains(jobId));
    }

    /**
     * Lignes de rafraîchissement d'une barre de progression ({@code tqdm} et assimilés). Elles
     * sont terminées par un {@code \r}, que le découpage en lignes — côté Java comme côté trainer
     * — traite comme une fin de ligne : <b>chaque rafraîchissement devient un évènement SSE</b>.
     * Un téléchargement HuggingFace en produit des centaines par seconde, de quoi retourner le
     * tampon de 500 du diffuseur en quelques secondes et chasser l'utile.
     */
    private static final java.util.regex.Pattern PROGRESS_BAR_PATTERN =
            java.util.regex.Pattern.compile("\\d+%\\||\\d+/\\d+ \\[|it/s|s/it|B/s");
    private static final long PROGRESS_BAR_MIN_INTERVAL_MS = 1_000;
    private final java.util.concurrent.atomic.AtomicLong lastProgressBarPublish =
            new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * Diffuse une ligne de suivi <b>et</b> la consigne dans la trace du job.
     *
     * <p>Un seul point de sortie pour les deux canaux : ce qu'on a vu passer en direct est
     * exactement ce qu'on relit après un rechargement. Deux chemins séparés divergeraient au
     * premier oubli.
     */
    private void publishAndRecord(String jobId, String level, String message) {
        switch (level) {
            case "ERROR" -> broadcaster.jobError(jobId, message);
            case "WARN" -> broadcaster.jobWarn(jobId, message);
            default -> broadcaster.jobInfo(jobId, message);
        }
        telemetryStore.appendLog(jobId, level, message);
    }

    /**
     * Lignes de sortie qui ne sont pas des informations : {@code stderr} est fusionné dans
     * {@code stdout} en amont ({@code redirectErrorStream}, {@code stderr=STDOUT}), si bien
     * qu'une trace Python et un avertissement CUDA arrivaient étiquetés INFO — rendus en bleu
     * comme le reste, et invisibles dans un flux qui défile.
     */
    private static final java.util.regex.Pattern ERROR_LINE = java.util.regex.Pattern.compile(
            // Les frontières \\b se placent AUTOUR des mots seulement : « Traceback (…): » finit
            // sur une parenthèse, et « OutOfMemoryError » n'a pas de frontière avant « Error » —
            // deux formes que la version à \\b global laissait passer pour des informations.
            "(?i)(traceback \\(most recent call last\\)|out of memory|"
                    + "\\b(erreur|error|fatal|exception|killed|aborted)\\b|\\werror\\b)");
    private static final java.util.regex.Pattern WARN_LINE = java.util.regex.Pattern.compile(
            "(?i)(\\b(avertissement|warn|warning)\\b|\\wwarning\\b|deprecat\\w*)");

    /** Niveau d'une ligne de sortie, à défaut de canal séparé. */
    static String levelOf(String line) {
        if (ERROR_LINE.matcher(line).find()) return "ERROR";
        if (WARN_LINE.matcher(line).find()) return "WARN";
        return "INFO";
    }

    /**
     * Préfixe d'une ligne <b>machine</b> émise par l'entraînement. Doit rester identique côté
     * Python ({@code scripts/spectra_events.py}) — c'est le seul point d'accord entre les deux.
     */
    static final String EVENT_SENTINEL = "__SPECTRA_EVENT__";

    /** Journalisation, diffusion SSE et extraction de progression, communes aux deux exécutions. */
    private void onProcessLine(String jobId, String label, String line) {
        // Une ligne structurée dit ce qu'elle est : plus rien à deviner sur sa forme.
        if (line.startsWith(EVENT_SENTINEL) && handleStructuredEvent(jobId, line)) {
            return;
        }
        // Repli : prose relue par expressions régulières. Ce chemin reste nécessaire — un
        // train.sh antérieur, ou une image de trainer non reconstruite, n'émet que cela — et
        // c'est aussi lui qui traite la sortie des bibliothèques tierces.
        // Extraction AVANT tout filtrage : une ligne non diffusée doit quand même faire avancer
        // la progression du job.
        if ("train".equals(label)) {
            parseTrainingOutput(jobId, line);
        }
        if (line.isBlank()) return;
        if (isProgressBarRefresh(line) && !allowProgressBarLine()) return;
        publishAndRecord(jobId, levelOf(line), line);   // SSE temps réel + trace relisible
    }

    /**
     * Traite une ligne d'évènement structuré.
     *
     * @return {@code true} si la ligne a été consommée ; {@code false} si elle est illisible —
     *         elle repart alors par le chemin ordinaire plutôt que d'être perdue, car c'est
     *         peut-être elle qui explique l'échec.
     */
    private boolean handleStructuredEvent(String jobId, String line) {
        try {
            com.fasterxml.jackson.databind.JsonNode event =
                    mapper.readTree(line.substring(EVENT_SENTINEL.length()).strip());
            String type = event.path("type").asText("");
            switch (type) {
                case "progress" -> {
                    if (!event.hasNonNull("epoch")) return false;
                    double epoch = event.get("epoch").asDouble();
                    Double loss = event.hasNonNull("loss") ? event.get("loss").asDouble() : null;
                    Double evalLoss = event.hasNonNull("evalLoss")
                            ? event.get("evalLoss").asDouble() : null;
                    updateJob(jobId, j -> j.withTrainingProgress(epoch, loss, evalLoss));
                    telemetryStore.appendLossPoint(jobId, epoch, loss, evalLoss);
                    // Le rendu lisible est produit ICI : le flux et la trace restent du texte,
                    // c'est le transport qui devient exact — pas ce que lit l'utilisateur.
                    String rendered = renderProgress(epoch, loss, evalLoss);
                    broadcaster.jobProgress(jobId, rendered, epoch, loss, evalLoss);
                    telemetryStore.appendLog(jobId, "INFO", rendered);
                    return true;
                }
                case "log" -> {
                    // Le niveau vient de l'émetteur, qui le connaît : plus d'heuristique sur des
                    // mots-clés, et donc plus de « Traceback (…): » classé en information.
                    String level = event.path("level").asText("INFO").toUpperCase();
                    String message = event.path("message").asText("");
                    if (message.isBlank()) return false;
                    publishAndRecord(jobId, level, message);
                    return true;
                }
                default -> {
                    return false;
                }
            }
        } catch (Exception e) {
            log.debug("Job {} : évènement structuré illisible, traité comme texte ({})",
                    jobId, e.getMessage());
            return false;
        }
    }

    /** Rendu humain d'une progression : « epoch 0.33 · loss 1.8421 · eval_loss 0.9500 ». */
    private static String renderProgress(double epoch, Double loss, Double evalLoss) {
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT,
                "  epoch %.2f", epoch));
        if (loss != null) sb.append(String.format(java.util.Locale.ROOT, " · loss %.4f", loss));
        if (evalLoss != null) {
            sb.append(String.format(java.util.Locale.ROOT, " · eval_loss %.4f", evalLoss));
        }
        return sb.toString();
    }

    /** Vrai pour un rafraîchissement de barre ; « 100% » passe toujours (fin d'étape lisible). */
    private static boolean isProgressBarRefresh(String line) {
        return !line.contains("100%") && PROGRESS_BAR_PATTERN.matcher(line).find();
    }

    /** Au plus une ligne de barre par seconde : le flux reste vivant sans être noyé. */
    private boolean allowProgressBarLine() {
        long now = System.currentTimeMillis();
        long last = lastProgressBarPublish.get();
        return now - last >= PROGRESS_BAR_MIN_INTERVAL_MS
                && lastProgressBarPublish.compareAndSet(last, now);
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
        publishAndRecord(jobId, "INFO",
                "Job " + jobId + " : fusion de l'adaptateur, conversion GGUF et enregistrement…");

        Path mergedDir = jobDir.resolve("merged");
        // Même résolution que l'entraînement (manifeste unique) : l'adaptateur LoRA n'est
        // fusionnable QUE sur le modèle de base exact qui l'a entraîné.
        ExportSpec spec = new ExportSpec(jobId, adapterPath, mergedDir, request.modelName(),
                baseModelCatalog.resolveHfRepo(request.baseModel()));

        int exitCode = trainingRunner.exportGguf(spec, line -> onProcessLine(jobId, "export", line),
                () -> cancelledJobs.contains(jobId));

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
                        spec.baseHfRepo(),
                        "q8_0", // quantisation appliquée par export_gguf.py (convert --outtype q8_0)
                        baseModelCatalog.find(request.baseModel())
                                .map(BaseModelCatalog.BaseModel::contextLength)
                                .orElse(null)));

        log.info("Job {}: modèle '{}' converti et enregistré → {}", jobId, request.modelName(), registeredPath);
        publishAndRecord(jobId, "INFO", "Job " + jobId + " : modèle '" + request.modelName()
                + "' enregistré et déployable → " + registeredPath);
        updateJob(jobId, j -> j.completed(registeredPath));

        chainEvaluation(jobId, request);
    }

    /**
     * Enchaîne l'évaluation du modèle qui vient d'être enregistré.
     *
     * <p>Appelée <b>après</b> le passage à COMPLETED, et volontairement : l'évaluation dure
     * plusieurs minutes, et retarder le statut du job jusqu'à son terme présenterait un
     * entraînement réussi comme encore en cours. Le job est terminal, l'évaluation vit sa vie ;
     * le lien entre les deux est {@code evaluationId}.
     *
     * <p>Un échec de soumission n'échoue <b>pas</b> le job : l'entraînement, lui, a bien abouti et
     * le modèle est déployable. Le dire dans le flux suffit — marquer FAILED un job dont
     * l'artefact est sur le disque serait un mensonge coûteux.
     */
    private void chainEvaluation(String jobId, FineTuningRequest request) {
        if (!Boolean.TRUE.equals(request.autoEvaluate())) return;
        try {
            String evalId = evaluationService.submit(
                    new fr.spectra.dto.EvaluationRequest(request.modelName(), null, jobId));
            updateJobEvenIfTerminal(jobId, j -> j.withEvaluation(evalId));
            publishAndRecord(jobId, "INFO", "Job " + jobId + " : évaluation " + evalId
                    + " lancée sur le modèle '" + request.modelName() + "'");
        } catch (Exception e) {
            log.warn("Job {} : évaluation enchaînée non lancée ({})", jobId, e.getMessage());
            publishAndRecord(jobId, "WARN", "Job " + jobId
                    + " : évaluation automatique non lancée — " + e.getMessage()
                    + " (le modèle reste enregistré et déployable)");
        }
    }

    /** Neutralise un nom de modèle pour l'utiliser comme nom de fichier GGUF. */
    private static String safeFileName(String modelName) {
        String cleaned = modelName.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-+", "-");
        cleaned = cleaned.replaceAll("^-+|-+$", "");
        return cleaned.isEmpty() ? "model" : cleaned;
    }

    /**
     * Époque courante, <b>fraction comprise</b> : {@code ProgressLogger} imprime
     * « epoch=0.33 ». Le motif ne capturait que la partie entière — donc {@code 0} pendant toute
     * la première époque, que l'UI interprétait comme « pas de progression connue ».
     */
    private static final java.util.regex.Pattern EPOCH_PATTERN =
            java.util.regex.Pattern.compile("epoch[= ]*(\\d+(?:\\.\\d+)?)");
    /**
     * Loss d'entraînement. Le {@code (?<!eval_)} est essentiel : sans lui, la ligne
     * « eval_loss=0.45 » satisfait aussi « loss=… » et l'{@code eval_loss} était enregistré
     * comme loss d'entraînement — écrasant silencieusement la vraie courbe.
     */
    private static final java.util.regex.Pattern LOSS_PATTERN =
            java.util.regex.Pattern.compile("(?<!eval_)loss[= ]*([\\d.]+)");
    private static final java.util.regex.Pattern EVAL_LOSS_PATTERN =
            java.util.regex.Pattern.compile("eval_loss[= ]*([\\d.]+)");

    /**
     * Parse les lignes de sortie du script pour mettre à jour la progression.
     * Formats émis par {@code ProgressLogger} : « epoch=2.00  loss=0.4523 » et,
     * quand un split de validation est actif, « epoch=2.00  eval_loss=0.4102 ».
     */
    private void parseTrainingOutput(String jobId, String line) {
        try {
            String lower = line.toLowerCase();
            if (!lower.contains("epoch")) return;
            Double epoch = extract(EPOCH_PATTERN, lower, Double::parseDouble);
            if (epoch == null) return;
            Double loss = extract(LOSS_PATTERN, lower, Double::parseDouble);
            Double evalLoss = extract(EVAL_LOSS_PATTERN, lower, Double::parseDouble);
            updateJob(jobId, j -> j.withTrainingProgress(epoch, loss, evalLoss));
            // Le job ne porte qu'une loss SCALAIRE — chaque ligne écrase la précédente. La série
            // n'existe que si on l'écrit au moment où on la voit passer.
            telemetryStore.appendLossPoint(jobId, epoch, loss, evalLoss);
        } catch (Exception e) {
            // Parsing best-effort, on ne casse pas le process pour une ligne mal formatée
        }
    }

    private static <T> T extract(java.util.regex.Pattern pattern, String text,
                                 java.util.function.Function<String, T> parser) {
        var matcher = pattern.matcher(text);
        return matcher.find() ? parser.apply(matcher.group(1)) : null;
    }

    private int countLines(Path file) throws Exception {
        try (var lines = Files.lines(file)) {
            return (int) lines.count();
        }
    }

    /**
     * Mise à jour d'un job <b>déjà terminal</b>, pour les seuls rattachements qui surviennent
     * après coup — aujourd'hui l'identifiant de l'évaluation enchaînée.
     *
     * <p>{@link #updateJob} refuse d'écrire sur un job terminal, et c'est ce qui empêche une
     * ligne de progression tardive de ressusciter un job annulé. Ici, le statut n'est pas
     * touché : on ajoute une référence à un travail lancé <i>parce que</i> le job a abouti.
     * Un job annulé reste néanmoins exclu — il n'a rien lancé.
     */
    private void updateJobEvenIfTerminal(String jobId,
                                         java.util.function.UnaryOperator<FineTuningJob> updater) {
        if (cancelledJobs.contains(jobId)) return;
        repository.findById(jobId).ifPresent(entity ->
                repository.save(FineTuningJobEntity.fromDto(updater.apply(entity.toDto()))));
    }

    private void updateJob(String jobId, java.util.function.UnaryOperator<FineTuningJob> updater) {
        // Ne jamais ressusciter un job annulé/terminal : une ligne de progression tardive
        // émise par le thread lecteur ne doit pas réécrire le statut TRAINING par-dessus le
        // FAILED posé par cancelJob (cancelledJobs.add() est effectué AVANT ce save).
        if (cancelledJobs.contains(jobId)) return;
        repository.findById(jobId).ifPresent(entity -> {
            FineTuningJob current = entity.toDto();
            if (current.status().isTerminal()) return;
            FineTuningJob updated = updater.apply(current);
            repository.save(FineTuningJobEntity.fromDto(updated));
        });
    }
}
