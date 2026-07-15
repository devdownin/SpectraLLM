package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.dto.InstallationJob;
import fr.spectra.dto.LlmFitRecommendation;
import fr.spectra.persistence.InstallationJobEntity;
import fr.spectra.persistence.InstallationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Conseiller de modèles — « quel LLM tiendra sur ma machine ? ».
 *
 * <p><b>Pourquoi.</b> Un modèle GGUF trop gros pour la RAM/VRAM disponible ne se charge pas
 * (ou swappe et devient inutilisable). Plutôt que de laisser l'utilisateur deviner, ce service
 * délègue à l'outil externe {@code llmfit} qui, à partir des contraintes matérielles (mémoire,
 * RAM, cœurs CPU), recommande des modèles compatibles et pilote leur téléchargement.</p>
 *
 * <p><b>Comment.</b> {@code llmfit} est invoqué en sous-processus (mode {@code --json}). Le
 * téléchargement émet une progression en pourcentage, capturée via {@code PROGRESS_PATTERN} et
 * rediffusée en temps réel aux clients par un {@link reactor.core.publisher.Sinks.Many} (SSE).
 * Les modèles atterrissent dans {@code models-dir}, qui <b>doit</b> correspondre au volume monté
 * par le conteneur llm-chat pour qu'ils soient immédiatement servables.</p>
 */
@Service
public class LlmFitService {

    private static final Logger log = LoggerFactory.getLogger(LlmFitService.class);
    private static final Pattern PROGRESS_PATTERN = Pattern.compile("(\\d+(\\.\\d+)?)%");
    /** Extrait un chemin/fichier GGUF d'une ligne de log (tolère du texte avant/après). */
    private static final Pattern GGUF_PATTERN = Pattern.compile("(\\S+\\.gguf)");

    // Ces valeurs proviennent de requêtes HTTP et sont passées en arguments à un
    // sous-processus (llmfit). ProcessBuilder(List) n'utilise pas de shell — pas
    // d'injection de métacaractères — mais un argument commençant par « - » serait
    // interprété par llmfit comme une OPTION (argument injection). On impose donc un
    // premier caractère alphanumérique et un jeu de caractères restreint.
    /** Identifiant de modèle façon HuggingFace (« org/name ») ou Ollama (« llama3.2:3b »). */
    private static final Pattern SAFE_MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,200}");
    /** Option courte (quantisation « Q4_K_M », mémoire « 8GB », …). */
    private static final Pattern SAFE_OPTION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,64}");

    /**
     * Valide une valeur destinée à la ligne de commande d'un sous-processus.
     *
     * @throws IllegalArgumentException si la valeur est nulle ou hors de l'allowlist
     *         (empêche notamment l'injection d'arguments via un « - » initial).
     */
    private static String requireSafeArg(String value, Pattern allowed, String field) {
        if (value == null || !allowed.matcher(value).matches()) {
            throw new IllegalArgumentException("Valeur invalide pour « " + field + " »");
        }
        return value;
    }

    private final ObjectMapper objectMapper;
    private final ModelRegistryService modelRegistryService;
    private final LlmChatClient chatClient;
    private final InstallationJobRepository installationRepository;
    private final Map<String, Sinks.Many<Integer>> progressSinks = new ConcurrentHashMap<>();

    /**
     * Modèles dont une installation est en cours. Sans ce verrou par modèle, deux POST
     * concurrents du même modèle lançaient deux sous-processus llmfit écrivant le même
     * fichier cible, écrasaient le sink SSE du premier et pouvaient, via le repli
     * {@link #findRecentGguf}, enregistrer l'alias vers le mauvais GGUF.
     */
    private final java.util.Set<String> installsInProgress = ConcurrentHashMap.newKeySet();
    /** Processus llmfit en cours, par jobId — pour pouvoir interrompre un téléchargement. */
    private final Map<String, Process> runningInstallProcesses = new ConcurrentHashMap<>();
    /** Annulations demandées, par jobId : distingue « annulé » d'un échec après destroy(). */
    private final java.util.Set<String> cancelRequestedInstalls = ConcurrentHashMap.newKeySet();

    /**
     * Exécuteur dédié aux installations : chaque téléchargement BLOQUE un thread jusqu'à
     * 60 minutes (lecture du sous-processus + copie de plusieurs Go). Les exécuter sur le
     * {@code ForkJoinPool.commonPool} (défaut de {@code supplyAsync}) affamerait tout autre
     * usage du pool commun de la JVM — même piège que celui documenté dans
     * {@link HybridSearchService}. Threads virtuels : parfaits pour de l'I/O bloquante.
     */
    private final java.util.concurrent.ExecutorService installExecutor =
            java.util.concurrent.Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("model-install-", 0).factory());

    @jakarta.annotation.PreDestroy
    void shutdownInstallExecutor() {
        // Interrompt les téléchargements en cours à l'arrêt de la JVM : la réconciliation
        // au prochain démarrage marquera leurs jobs FAILED.
        installExecutor.shutdownNow();
    }

    @Value("${llmfit.path:llmfit}")
    private String llmfitPath;

    /**
     * Répertoire cible des modèles téléchargés.
     * Doit correspondre au volume monté par llm-chat (./data/models → /models).
     * Dans le conteneur spectra-api : ./data/models = /app/data/models (volume ./data:/app/data).
     */
    @Value("${llmfit.models-dir:./data/models}")
    private String modelsDirPath;

    public LlmFitService(ObjectMapper objectMapper, ModelRegistryService modelRegistryService,
                         LlmChatClient chatClient, InstallationJobRepository installationRepository) {
        this.objectMapper = objectMapper;
        this.modelRegistryService = modelRegistryService;
        this.chatClient = chatClient;
        this.installationRepository = installationRepository;
    }

    /**
     * Au démarrage, tout job d'installation resté non-terminal (PENDING/DOWNLOADING/REGISTERING)
     * est orphelin : son sous-processus {@code llmfit} a disparu avec l'ancienne JVM. On le marque
     * FAILED pour donner un historique honnête au lieu d'un téléchargement figé à jamais.
     *
     * <p>Miroir de {@code FineTuningService.reconcileInterruptedJobs()}.</p>
     */
    @jakarta.annotation.PostConstruct
    void reconcileInterruptedInstallations() {
        try {
            for (InstallationJobEntity e : installationRepository.findAll()) {
                InstallationJob j = e.toDto();
                if (j.status() != InstallationJob.Status.COMPLETED
                        && j.status() != InstallationJob.Status.FAILED) {
                    installationRepository.save(InstallationJobEntity.fromDto(
                            j.failed("Interrompu par un redémarrage du serveur")));
                    log.warn("Installation {} ({}) marquée FAILED : interrompue par un redémarrage ({})",
                            j.jobId(), j.status(), j.modelName());
                }
            }
        } catch (Exception ex) {
            log.warn("Réconciliation des installations interrompues impossible: {}", ex.getMessage());
        }
    }

    public LlmFitRecommendation getRecommendations(int limit, String memory, String ram, Integer cpuCores) {
        // Validation AVANT le try : une valeur de simulation invalide (ex. « 12 G ») doit
        // remonter en 400 explicite, pas être avalée en « aucune recommandation ».
        if (memory != null && !memory.isBlank()) requireSafeArg(memory, SAFE_OPTION, "memory");
        if (ram != null && !ram.isBlank()) requireSafeArg(ram, SAFE_OPTION, "ram");

        try {
            List<String> command = new ArrayList<>(List.of(llmfitPath, "recommend", "--json", "--limit", String.valueOf(limit)));
            if (memory != null && !memory.isBlank()) {
                command.add("--memory");
                command.add(memory);
            }
            if (ram != null && !ram.isBlank()) {
                command.add("--ram");
                command.add(ram);
            }
            if (cpuCores != null) {
                command.add("--cpu-cores");
                command.add(String.valueOf(cpuCores));
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();

            LlmFitRecommendation recommendation;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                recommendation = objectMapper.readValue(reader, LlmFitRecommendation.class);
            }
            // Le JSON est déjà lu : borne courte pour ne pas bloquer le thread si llmfit traîne.
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            return enrichWithRegistry(recommendation);
        } catch (Exception e) {
            log.error("Erreur lors de la récupération des recommandations llmfit", e);
            return new LlmFitRecommendation(Collections.emptyList(), null);
        }
    }

    /**
     * Croise le drapeau {@code installed} de llmfit avec le registre Spectra : llmfit ne
     * connaît que SON cache, alors que la vérité d'installation est le registre (le GGUF
     * est copié dans le volume partagé et enregistré avec {@code hfRepo} = identifiant
     * llmfit). Un modèle installé via Spectra reste ainsi marqué « installé » même si le
     * cache llmfit est purgé — et le comparatif du Model Hub reflète l'état réel.
     */
    LlmFitRecommendation enrichWithRegistry(LlmFitRecommendation recommendation) {
        if (recommendation == null || recommendation.models() == null || recommendation.models().isEmpty()) {
            return recommendation;
        }
        java.util.Set<String> registered = modelRegistryService.listModels("chat").stream()
                .map(model -> model.get("hfRepo"))
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());

        List<LlmFitRecommendation.ModelRecommendation> enriched = recommendation.models().stream()
                .map(model -> registered.contains(model.name()) && !Boolean.TRUE.equals(model.installed())
                        ? model.withInstalled(true)
                        : model)
                .toList();
        return new LlmFitRecommendation(enriched, recommendation.system());
    }

    public Flux<Integer> getInstallationProgress(String modelName) {
        // Pas de sink = aucun téléchargement connu de cette JVM (ex. reprise de l'UI après
        // un redémarrage de l'API) : compléter immédiatement pour que l'EventSource se ferme
        // côté client, au lieu de créer un flux muet qui laisserait la barre figée à jamais.
        Sinks.Many<Integer> sink = progressSinks.get(modelName);
        return sink != null ? sink.asFlux() : Flux.empty();
    }

    public CompletableFuture<Boolean> installModel(String modelName, String quant, boolean autoActivate) {
        // Validation synchrone AVANT le sous-processus : rejette une entrée malveillante
        // (ex. modelName = « --output=/etc/… ») immédiatement, sans démarrer de tâche async.
        requireSafeArg(modelName, SAFE_MODEL_ID, "modelName");
        if (quant != null && !quant.isBlank()) {
            requireSafeArg(quant, SAFE_OPTION, "quant");
        }

        // Un seul téléchargement à la fois PAR modèle (cf. installsInProgress). Le verrou est
        // relâché dans le finally de la tâche asynchrone, quel que soit le dénouement.
        if (!installsInProgress.add(modelName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Un téléchargement de « " + modelName + " » est déjà en cours");
        }

        // Sink neuf à chaque install (remplace un éventuel sink terminé d'un run précédent
        // pour ce modèle). On le CONSERVE après complétion : le navigateur ne s'abonne au
        // flux SSE qu'après le retour du POST, donc un téléchargement rapide ou en cache
        // termine avant l'abonnement — `replay().latest()` rejoue alors l'état terminal
        // (100 % + complete / erreur) à l'abonné tardif au lieu de laisser la barre bloquée.
        Sinks.Many<Integer> sink = Sinks.many().replay().latest();
        progressSinks.put(modelName, sink);

        // Job persisté (H2) AVANT la tâche async : il apparaît immédiatement dans l'historique
        // et, s'il reste non-terminal après un crash/redémarrage, la réconciliation au démarrage
        // le marquera FAILED. Le sink SSE en mémoire ne survivant pas à la JVM, la base est la
        // seule trace durable du téléchargement (cf. FineTuningService).
        String jobId = UUID.randomUUID().toString();
        try {
            installationRepository.save(InstallationJobEntity.fromDto(
                    InstallationJob.pending(jobId, modelName, quant, autoActivate)));
        } catch (RuntimeException e) {
            // Échec avant la prise en charge asynchrone : ne pas laisser le verrou posé.
            installsInProgress.remove(modelName);
            throw e;
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Démarrage de l'installation du modèle {} avec llmfit (quant={}, autoActivate={}, job={})",
                        modelName, quant != null ? quant : "auto", autoActivate, jobId);
                updateInstallation(jobId, j -> j.withStatus(InstallationJob.Status.DOWNLOADING, "Téléchargement"));

                // Référence temporelle pour le repli par scan : tout GGUF apparu dans
                // models-dir après cet instant est un candidat produit par ce téléchargement.
                Instant downloadStart = Instant.now();

                List<String> command = new ArrayList<>(List.of(llmfitPath, "download", modelName));
                if (quant != null && !quant.isBlank()) {
                    command.add("--quant");
                    command.add(quant);
                }

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true); // Merge stdout and stderr for progress tracking
                Process process = pb.start();
                runningInstallProcesses.put(jobId, process);

                String downloadedFile = null;
                int lastPersistedProgress = -1;
                String lastOutputLine = null;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.debug("[llmfit] {}", line);
                        if (!line.isBlank()) {
                            lastOutputLine = line.trim();
                        }

                        // Progress tracking
                        Matcher m = PROGRESS_PATTERN.matcher(line);
                        if (m.find()) {
                            try {
                                // Plafonné à 99 : le « 100 % » de llmfit ne marque que la fin du
                                // TÉLÉCHARGEMENT — la copie dans le volume partagé (plusieurs Go)
                                // et l'enregistrement restent à faire. L'UI traite 100 comme
                                // terminal ; le vrai 100 est émis après l'enregistrement.
                                int progress = Math.min(99, (int) Double.parseDouble(m.group(1)));
                                sink.tryEmitNext(progress);
                                // Persiste la progression seulement quand l'entier avance :
                                // borne les écritures (~100 max) et garde l'historique à jour
                                // pour l'UI même sans abonné SSE (reprise après redémarrage).
                                if (progress > lastPersistedProgress) {
                                    lastPersistedProgress = progress;
                                    updateInstallation(jobId, j -> j.withProgress(progress));
                                }
                            } catch (Exception ignored) {}
                        }

                        // Capture le chemin du GGUF produit. On déclenche sur les libellés
                        // usuels (download/saved/complete) et on extrait le token *.gguf par
                        // regex — robuste aux variations de formulation et au texte annexe.
                        if (line.contains(".gguf")) {
                            String lower = line.toLowerCase(Locale.ROOT);
                            if (lower.contains("download") || lower.contains("saved") || lower.contains("complete")) {
                                Matcher gm = GGUF_PATTERN.matcher(line);
                                if (gm.find()) downloadedFile = gm.group(1);
                            }
                        }
                    }
                }

                boolean finished = process.waitFor(60, TimeUnit.MINUTES);
                if (!finished) {
                    process.destroyForcibly();
                    log.error("Timeout installation du modèle {} — process tué", modelName);
                    sink.tryEmitError(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Timeout after 60 minutes"));
                    updateInstallation(jobId, j -> j.failed("Délai dépassé (60 minutes) — process interrompu"));
                    return false;
                }
                if (cancelRequestedInstalls.contains(jobId)) {
                    // Annulé pendant le téléchargement (process tué) ou juste avant
                    // l'enregistrement : ne pas copier/enregistrer un modèle non voulu.
                    log.info("Installation de {} annulée par l'utilisateur (job {})", modelName, jobId);
                    sink.tryEmitError(new ResponseStatusException(HttpStatus.CONFLICT,
                            "Téléchargement annulé par l'utilisateur"));
                    return false;
                }
                if (process.exitValue() == 0) {
                    log.info("Modèle {} installé avec succès", modelName);
                    updateInstallation(jobId, j -> j.withStatus(InstallationJob.Status.REGISTERING,
                            "Enregistrement du modèle"));

                    if (downloadedFile == null) {
                        // Repli : la sortie de llmfit n'a pas permis d'extraire le chemin
                        // (format de log changé ?). Si llmfit a écrit directement dans
                        // models-dir, un scan des GGUF récents retrouve le fichier.
                        downloadedFile = findRecentGguf(Path.of(modelsDirPath), downloadStart, modelName)
                                .map(Path::toString)
                                .orElse(null);
                        if (downloadedFile != null) {
                            log.info("Chemin GGUF non détecté dans la sortie llmfit — retrouvé par scan "
                                    + "de {} : {}", modelsDirPath, downloadedFile);
                        }
                    }

                    if (downloadedFile != null) {
                        Path source   = Path.of(downloadedFile);
                        String fileName = source.getFileName().toString();
                        String alias    = fileName.replace(".gguf", "");

                        /*
                         * Ensure the GGUF is in the shared models volume so that
                         * llm-chat (mounted at ./data/models:/models) can serve it.
                         * llmfit may download to its own cache (e.g. ~/.llmfit/…);
                         * we always copy to modelsDirPath so the path registered in
                         * registry.json is reachable by every container.
                         */
                        Path modelsDir = Path.of(modelsDirPath);
                        Path target    = modelsDir.resolve(fileName);
                        if (!source.toAbsolutePath().equals(target.toAbsolutePath())) {
                            Files.createDirectories(modelsDir);
                            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                            log.info("Modèle copié dans le volume partagé : {} → {}", source, target);
                        }
                        String registeredPath = target.toAbsolutePath().toString();

                        modelRegistryService.registerChatModel(
                            alias,
                            registeredPath,
                            "Tu es un assistant IA spécialisé.",
                            Collections.emptyMap(),
                            "llmfit",
                            // Traçabilité : identifiant demandé à llmfit (repo HF ou tag Ollama)
                            // et quantisation choisie, pour que le registre soit auto-descriptif.
                            new ModelRegistryService.ModelOrigin(
                                    modelName,
                                    quant != null && !quant.isBlank() ? quant : null,
                                    null)
                        );
                        log.info("Modèle enregistré dans Spectra sous l'alias '{}' → {}", alias, registeredPath);

                        if (autoActivate) {
                            // Mémorise le modèle actif AVANT bascule : il devient la baseline
                            // proposée pour le benchmark qualité (boucle « comparatif → qualité »).
                            String previousActive = chatClient.getActiveModel();
                            if (previousActive != null && !previousActive.isBlank()
                                    && !previousActive.equals(alias)) {
                                updateInstallation(jobId, j -> j.withPreviousActiveModel(previousActive));
                            }
                            // Route via le chat client (et non le registre seul) pour aligner
                            // l'activation sur le reste : registre + orchestrateur runtime
                            // (hot-reload en mode embarqué) + modèle actif en mémoire + vérif.
                            // En mode conteneur séparé, l'entrypoint superviseur de llm-chat
                            // lit le pointeur du registre et recharge le modèle tout seul.
                            chatClient.setActiveModel(alias);
                            log.info("Modèle '{}' activé — llm-chat converge automatiquement "
                                    + "(pointeur du registre) sous quelques secondes.", alias);
                        }
                        String finalPath = registeredPath;
                        updateInstallation(jobId, j -> j.completed(finalPath, "Terminé"));
                    } else {
                        // Téléchargement réussi mais chemin GGUF introuvable (ni dans la sortie
                        // llmfit, ni par scan de models-dir) : le modèle n'a pu être ni copié dans
                        // le volume partagé ni enregistré. On le signale explicitement plutôt que
                        // de laisser un faux succès silencieux.
                        log.warn("Modèle '{}' téléchargé (exit 0) mais aucun fichier .gguf détecté (sortie "
                                + "llmfit et scan de {}) — enregistrement/activation ignorés. Vérifiez le "
                                + "format de sortie de llmfit ou enregistrez le modèle manuellement.",
                                modelName, modelsDirPath);
                        updateInstallation(jobId, j -> j.completed(null,
                                "Téléchargé mais fichier GGUF introuvable — non enregistré"));
                    }

                    // 100 % + complete émis SEULEMENT une fois copie + enregistrement (+ éventuelle
                    // activation) terminés : avant, l'UI annonçait « saved to the registry » pendant
                    // que la copie de plusieurs Go tournait encore, et le CTA benchmark interrogeait
                    // l'historique trop tôt (job encore REGISTERING → jamais proposé).
                    sink.tryEmitNext(100);
                    sink.tryEmitComplete();
                    return true;
                } else {
                    String errorMsg = lastOutputLine != null ? lastOutputLine : ("Exit code " + process.exitValue());
                    log.error("Échec de l'installation du modèle {} : {}", modelName, errorMsg);
                    sink.tryEmitError(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, errorMsg));
                    updateInstallation(jobId, j -> j.failed(errorMsg));
                    return false;
                }
            } catch (Exception e) {
                log.error("Erreur lors de l'installation du modèle " + modelName, e);
                sink.tryEmitError(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Erreur d'installation: " + e.getMessage(), e));
                updateInstallation(jobId, j -> j.failed(e.getMessage() != null ? e.getMessage() : e.toString()));
                return false;
            } finally {
                // Relâche le verrou par modèle quel que soit le dénouement — un nouveau
                // téléchargement du même modèle redevient possible immédiatement.
                installsInProgress.remove(modelName);
                runningInstallProcesses.remove(jobId);
                cancelRequestedInstalls.remove(jobId);
            }
            // Le sink n'est volontairement pas retiré ici (cf. note à la création) :
            // il est conservé pour rejouer l'état terminal aux abonnés tardifs et sera
            // remplacé au prochain install du même modèle.
        }, installExecutor);
    }

    /** Historique des installations Model Hub (les plus récentes d'abord). */
    public List<InstallationJob> getInstallations() {
        return installationRepository.findAll().stream()
                .map(InstallationJobEntity::toDto)
                .sorted(Comparator.comparing(InstallationJob::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** Un job d'installation par identifiant, ou {@code null} s'il est inconnu. */
    public InstallationJob getInstallation(String jobId) {
        return installationRepository.findById(jobId).map(InstallationJobEntity::toDto).orElse(null);
    }

    /**
     * Applique une transition au job persisté sans jamais ressusciter un job terminal :
     * une ligne de progression tardive ne doit pas réécrire par-dessus un FAILED/COMPLETED.
     * Miroir de {@code FineTuningService.updateJob}.
     */
    private void updateInstallation(String jobId, UnaryOperator<InstallationJob> updater) {
        installationRepository.findById(jobId).ifPresent(entity -> {
            InstallationJob current = entity.toDto();
            if (current.status() == InstallationJob.Status.COMPLETED
                    || current.status() == InstallationJob.Status.FAILED
                    || current.status() == InstallationJob.Status.CANCELLED) return;
            installationRepository.save(InstallationJobEntity.fromDto(updater.apply(current)));
        });
    }

    /**
     * Annule un téléchargement en cours : le processus llmfit est tué, le job passe à
     * CANCELLED, le fichier partiellement téléchargé du cache llmfit est réutilisable
     * au prochain essai. Sans effet ({@code false}) si le job est inconnu ou déjà terminal.
     */
    public boolean cancelInstall(String jobId) {
        InstallationJob job = getInstallation(jobId);
        if (job == null || job.status() == InstallationJob.Status.COMPLETED
                || job.status() == InstallationJob.Status.FAILED
                || job.status() == InstallationJob.Status.CANCELLED) {
            return false;
        }
        cancelRequestedInstalls.add(jobId);
        updateInstallation(jobId, InstallationJob::cancelled);
        Process process = runningInstallProcesses.get(jobId);
        if (process != null) {
            process.destroy();
            log.info("Téléchargement {} ({}) : processus llmfit interrompu à la demande de "
                    + "l'utilisateur", jobId, job.modelName());
        }
        return true;
    }

    /**
     * Inventaire du volume des modèles : chaque GGUF présent avec sa taille, les alias
     * du registre qui le référencent et son éventuel statut actif. Ferme le cycle de vie
     * (télécharger → activer → <b>retirer</b>) en rendant visible l'espace consommé —
     * les GGUF pèsent plusieurs Go chacun et s'accumulaient sans aucune vue d'ensemble.
     */
    public Map<String, Object> getStorageReport() {
        Path modelsDir = Path.of(modelsDirPath).toAbsolutePath().normalize();
        List<Map<String, Object>> registered = new ArrayList<>();
        registered.addAll(modelRegistryService.listModels("chat"));
        registered.addAll(modelRegistryService.listModels("embedding"));

        List<Map<String, Object>> files = new ArrayList<>();
        long totalBytes = 0;
        if (Files.isDirectory(modelsDir)) {
            try (Stream<Path> entries = Files.list(modelsDir)) {
                List<Path> ggufs = entries
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf"))
                        .sorted()
                        .toList();
                for (Path gguf : ggufs) {
                    String fileName = gguf.getFileName().toString();
                    long size;
                    try {
                        size = Files.size(gguf);
                    } catch (Exception e) {
                        size = 0;
                    }
                    totalBytes += size;

                    List<Map<String, Object>> references = registered.stream()
                            .filter(model -> {
                                Object source = model.get("source");
                                return source != null
                                        && fileName.equals(Path.of(source.toString()).getFileName().toString());
                            })
                            .toList();

                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("file", fileName);
                    entry.put("sizeBytes", size);
                    entry.put("registeredAs", references.stream()
                            .map(model -> Map.of(
                                    "name", model.get("name"),
                                    "type", model.get("type"),
                                    "active", Boolean.TRUE.equals(model.get("active"))))
                            .toList());
                    entry.put("active", references.stream()
                            .anyMatch(model -> Boolean.TRUE.equals(model.get("active"))));
                    files.add(entry);
                }
            } catch (Exception e) {
                log.warn("Inventaire du répertoire des modèles impossible : {}", e.getMessage());
            }
        }

        Map<String, Object> report = new java.util.LinkedHashMap<>();
        report.put("modelsDir", modelsDir.toString());
        report.put("totalBytes", totalBytes);
        report.put("files", files);
        return report;
    }

    /**
     * Repli de détection : le GGUF le plus récent apparu dans {@code dir} depuis {@code since}.
     * Best-effort : ne couvre que le cas où llmfit écrit directement dans models-dir (s'il
     * télécharge dans son propre cache, seul le parsing de sa sortie connaît le chemin).
     */
    static Optional<Path> findRecentGguf(Path dir, Instant since) {
        return findRecentGguf(dir, since, null);
    }

    /**
     * Variante corrélée au modèle demandé : parmi les GGUF récents, préfère un fichier dont
     * le nom correspond à {@code modelName}. Sans cette corrélation, deux téléchargements
     * parallèles de modèles différents pouvaient se voler leur fichier (« le plus récent »
     * étant celui de l'AUTRE install) et enregistrer un alias vers le mauvais GGUF.
     * Repli sur le plus récent si aucun nom ne correspond.
     */
    static Optional<Path> findRecentGguf(Path dir, Instant since, String modelName) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> recents = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf"))
                    .filter(p -> lastModified(p).isAfter(since.minusSeconds(1)))
                    .toList();
            if (recents.isEmpty()) {
                return Optional.empty();
            }
            String base = normalizedBaseName(modelName);
            if (base != null) {
                Optional<Path> nameMatch = recents.stream()
                        .filter(p -> normalizeToken(p.getFileName().toString()).contains(base))
                        .max(Comparator.comparing(LlmFitService::lastModified));
                if (nameMatch.isPresent()) {
                    return nameMatch;
                }
            }
            return recents.stream().max(Comparator.comparing(LlmFitService::lastModified));
        } catch (Exception e) {
            log.debug("Scan de {} impossible : {}", dir, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Nom de base normalisé d'un identifiant de modèle (« TheBloke/Llama-2-7B-GGUF » →
     * « llama27bgguf », « llama3.2:3b » → « llama32 ») pour une comparaison tolérante
     * avec les noms de fichiers GGUF. {@code null} si rien d'exploitable.
     */
    private static String normalizedBaseName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        String base = modelName.substring(modelName.lastIndexOf('/') + 1);
        int colon = base.indexOf(':');
        if (colon > 0) {
            base = base.substring(0, colon);
        }
        String normalized = normalizeToken(base);
        // Les repos HF se terminent souvent par « -GGUF » alors que le fichier insère la
        // quantisation avant l'extension (« llama-2-7b.Q4_K_M.gguf ») : on retire ce suffixe
        // du nom de base pour que la comparaison par inclusion fonctionne.
        if (normalized.endsWith("gguf") && normalized.length() > 4) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        return normalized.isBlank() ? null : normalized;
    }

    /** Minuscules + alphanumérique uniquement, pour comparer identifiants et noms de fichiers. */
    private static String normalizeToken(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = Character.toLowerCase(value.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }
}
