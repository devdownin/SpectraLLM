package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.dto.LlmFitRecommendation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
    private final Map<String, Sinks.Many<Integer>> progressSinks = new ConcurrentHashMap<>();

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
                         LlmChatClient chatClient) {
        this.objectMapper = objectMapper;
        this.modelRegistryService = modelRegistryService;
        this.chatClient = chatClient;
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

        // Sink neuf à chaque install (remplace un éventuel sink terminé d'un run précédent
        // pour ce modèle). On le CONSERVE après complétion : le navigateur ne s'abonne au
        // flux SSE qu'après le retour du POST, donc un téléchargement rapide ou en cache
        // termine avant l'abonnement — `replay().latest()` rejoue alors l'état terminal
        // (100 % + complete / erreur) à l'abonné tardif au lieu de laisser la barre bloquée.
        Sinks.Many<Integer> sink = Sinks.many().replay().latest();
        progressSinks.put(modelName, sink);

        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Démarrage de l'installation du modèle {} avec llmfit (quant={}, autoActivate={})",
                        modelName, quant != null ? quant : "auto", autoActivate);

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

                String downloadedFile = null;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.debug("[llmfit] {}", line);

                        // Progress tracking
                        Matcher m = PROGRESS_PATTERN.matcher(line);
                        if (m.find()) {
                            try {
                                int progress = (int) Double.parseDouble(m.group(1));
                                sink.tryEmitNext(progress);
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
                    sink.tryEmitError(new RuntimeException("Timeout after 60 minutes"));
                    return false;
                }
                if (process.exitValue() == 0) {
                    log.info("Modèle {} installé avec succès", modelName);
                    sink.tryEmitNext(100);
                    sink.tryEmitComplete();

                    if (downloadedFile == null) {
                        // Repli : la sortie de llmfit n'a pas permis d'extraire le chemin
                        // (format de log changé ?). Si llmfit a écrit directement dans
                        // models-dir, un scan des GGUF récents retrouve le fichier.
                        downloadedFile = findRecentGguf(Path.of(modelsDirPath), downloadStart)
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
                            // Route via le chat client (et non le registre seul) pour aligner
                            // l'activation sur le reste : registre + orchestrateur runtime
                            // (hot-reload en mode embarqué) + modèle actif en mémoire + vérif.
                            // En mode conteneur séparé, l'entrypoint superviseur de llm-chat
                            // lit le pointeur du registre et recharge le modèle tout seul.
                            chatClient.setActiveModel(alias);
                            log.info("Modèle '{}' activé — llm-chat converge automatiquement "
                                    + "(pointeur du registre) sous quelques secondes.", alias);
                        }
                    } else {
                        // Téléchargement réussi mais chemin GGUF introuvable (ni dans la sortie
                        // llmfit, ni par scan de models-dir) : le modèle n'a pu être ni copié dans
                        // le volume partagé ni enregistré. On le signale explicitement plutôt que
                        // de laisser un faux succès silencieux.
                        log.warn("Modèle '{}' téléchargé (exit 0) mais aucun fichier .gguf détecté (sortie "
                                + "llmfit et scan de {}) — enregistrement/activation ignorés. Vérifiez le "
                                + "format de sortie de llmfit ou enregistrez le modèle manuellement.",
                                modelName, modelsDirPath);
                    }

                    return true;
                } else {
                    log.error("Échec de l'installation du modèle {} (exit code: {})", modelName, process.exitValue());
                    sink.tryEmitError(new RuntimeException("Exit code: " + process.exitValue()));
                    return false;
                }
            } catch (Exception e) {
                log.error("Erreur lors de l'installation du modèle " + modelName, e);
                sink.tryEmitError(e);
                return false;
            }
            // Le sink n'est volontairement pas retiré ici (cf. note à la création) :
            // il est conservé pour rejouer l'état terminal aux abonnés tardifs et sera
            // remplacé au prochain install du même modèle.
        });
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
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf"))
                    .filter(p -> lastModified(p).isAfter(since.minusSeconds(1)))
                    .max(Comparator.comparing(LlmFitService::lastModified));
        } catch (Exception e) {
            log.debug("Scan de {} impossible : {}", dir, e.getMessage());
            return Optional.empty();
        }
    }

    private static Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }
}
