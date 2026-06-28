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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                return objectMapper.readValue(reader, LlmFitRecommendation.class);
            }
        } catch (Exception e) {
            log.error("Erreur lors de la récupération des recommandations llmfit", e);
            return new LlmFitRecommendation(Collections.emptyList(), null);
        }
    }

    public Flux<Integer> getInstallationProgress(String modelName) {
        return progressSinks.computeIfAbsent(modelName,
                k -> Sinks.many().replay().latest())
                .asFlux();
    }

    public CompletableFuture<Boolean> installModel(String modelName, String quant, boolean autoActivate) {
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
                            "llmfit"
                        );
                        log.info("Modèle enregistré dans Spectra sous l'alias '{}' → {}", alias, registeredPath);

                        if (autoActivate) {
                            // Route via le chat client (et non le registre seul) pour aligner
                            // l'activation sur le reste : registre + orchestrateur runtime
                            // (hot-reload en mode embarqué) + modèle actif en mémoire + vérif.
                            // En mode conteneur séparé, un redémarrage de llm-chat reste requis.
                            chatClient.setActiveModel(alias);
                            log.info("Modèle '{}' activé automatiquement (en mode conteneur séparé, "
                                    + "redémarrez llm-chat pour le charger).", alias);
                        }
                    } else {
                        // Téléchargement réussi mais chemin GGUF non détecté dans la sortie llmfit :
                        // le modèle n'a pu être ni copié dans le volume partagé ni enregistré.
                        // On le signale explicitement plutôt que de laisser un faux succès silencieux.
                        log.warn("Modèle '{}' téléchargé (exit 0) mais aucun chemin .gguf détecté dans la sortie "
                                + "llmfit — enregistrement/activation ignorés. Vérifiez le format de sortie de llmfit "
                                + "ou enregistrez le modèle manuellement.", modelName);
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
}
