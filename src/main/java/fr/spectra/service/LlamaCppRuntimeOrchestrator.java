package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.ResourceProfile;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestration runtime d'une instance locale `llama-server` pour le chat.
 *
 * <p>Le but est de faire correspondre l'alias actif du registre local
 * au modele effectivement servi par le process llama.cpp.</p>
 */
@Service
@ConditionalOnProperty(prefix = "spectra.llm", name = "provider", havingValue = "llama-cpp")
public class LlamaCppRuntimeOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(LlamaCppRuntimeOrchestrator.class);
    private static final Duration HEALTH_POLL_INTERVAL = Duration.ofSeconds(1);

    private final ModelRegistryService modelRegistry;
    private final SpectraProperties.RuntimeProperties runtime;
    private final ResourceAdvisorService resourceAdvisor;
    private final WebClient readinessClient;

    private RuntimeHandle chatRuntime;

    public LlamaCppRuntimeOrchestrator(ModelRegistryService modelRegistry,
                                       SpectraProperties properties,
                                       ResourceAdvisorService resourceAdvisor) {
        this.modelRegistry = modelRegistry;
        this.resourceAdvisor = resourceAdvisor;
        this.runtime = properties.llm() != null && properties.llm().runtime() != null
                ? properties.llm().runtime()
                : new SpectraProperties.RuntimeProperties(false, null, null, null, 0, 0, 0, 0, 0, List.of());

        String readinessBaseUrl = "http://127.0.0.1:" + this.runtime.effectivePort();

        this.readinessClient = WebClient.builder()
                .baseUrl(readinessBaseUrl)
                .build();
    }

    @PostConstruct
    void init() {
        if (!runtime.enabled()) {
            log.info("Orchestration runtime llama.cpp désactivée");
            return;
        }

        String activeModel = modelRegistry.getActiveChatModel();
        if (activeModel == null || activeModel.isBlank()) {
            log.info("Aucun modèle de chat actif à servir au démarrage");
            return;
        }

        try {
            ensureChatModelServed(activeModel);
        } catch (Exception e) {
            log.warn("Impossible de démarrer llama-server pour le modèle actif '{}': {}", activeModel, e.getMessage());
        }
    }

    public synchronized void ensureChatModelServed(String alias) {
        if (!runtime.enabled()) {
            return;
        }

        ModelRegistryService.RegisteredModel model = modelRegistry.getModel(alias, "chat")
                .orElseThrow(() -> new IllegalStateException("Modèle de chat inconnu dans le registre: " + alias));

        Path modelPath = resolveServableModelPath(model);

        if (chatRuntime != null && chatRuntime.isAlive()
                && alias.equals(chatRuntime.alias())
                && modelPath.equals(chatRuntime.modelPath())) {
            return;
        }

        stopChatRuntime();
        startChatRuntime(alias, modelPath);
    }

    public synchronized Map<String, Object> runtimeStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", runtime.enabled());

        if (chatRuntime == null) {
            status.put("running", false);
            return status;
        }

        status.put("running", chatRuntime.isAlive());
        status.put("alias", chatRuntime.alias());
        status.put("modelPath", chatRuntime.modelPath().toString());
        status.put("pid", chatRuntime.pid());
        status.put("port", runtime.effectivePort());
        return status;
    }

    @PreDestroy
    synchronized void shutdown() {
        stopChatRuntime();
    }

    private void startChatRuntime(String alias, Path modelPath) {
        try {
            List<String> command = buildChatCommand(alias, modelPath);
            log.info("Démarrage llama-server: {}", String.join(" ", command));

            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .directory(Path.of(runtime.effectiveWorkingDir()).toFile())
                    .redirectErrorStream(true);

            Process process = processBuilder.start();
            chatRuntime = new RuntimeHandle(alias, modelPath, process);

            Thread.ofVirtual().name("llama-cpp-chat-log").start(() -> streamLogs(process));

            waitUntilReady(alias);

            log.info("llama-server prêt pour le modèle '{}'", alias);
        } catch (Exception e) {
            stopChatRuntime();
            throw new IllegalStateException("Impossible de démarrer llama-server pour '" + alias + "': " + e.getMessage(), e);
        }
    }

    private void stopChatRuntime() {
        if (chatRuntime == null) {
            return;
        }

        Process process = chatRuntime.process();
        if (process.isAlive()) {
            log.info("Arrêt de llama-server pour le modèle '{}'", chatRuntime.alias());
            process.destroy();
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IllegalStateException("Interrompu pendant l'arrêt de llama-server", e);
            }
        }
        chatRuntime = null;
    }

    private Path resolveServableModelPath(ModelRegistryService.RegisteredModel model) {
        String sourceType = model.sourceType();
        String source = model.source();

        if (source == null || source.isBlank()) {
            throw new IllegalStateException("La source du modèle '" + model.name() + "' est vide");
        }

        if (!"gguf".equals(sourceType) && !"file".equals(sourceType)) {
            throw new IllegalStateException(
                    "Le modèle '" + model.name() + "' n'est pas directement servisable: sourceType=" + sourceType
                    + ". Enregistrez un chemin GGUF pour permettre l'orchestration runtime."
            );
        }

        Path modelPath = Path.of(source).toAbsolutePath().normalize();
        if (!Files.exists(modelPath)) {
            throw new IllegalStateException("Fichier modèle introuvable: " + modelPath);
        }
        return modelPath;
    }

    private List<String> buildChatCommand(String alias, Path modelPath) {
        // Recommandations auto-détectées (overridées par la config si explicitement définie)
        ResourceProfile.LlamaServerParams recommended =
                resourceAdvisor.getProfile().chatRecommendation();

        int threads = runtime.effectiveThreads() > 0
                ? runtime.effectiveThreads()
                : recommended.threads();

        int contextSize = runtime.effectiveContextSize() > 0
                ? runtime.effectiveContextSize()
                : recommended.contextSize();

        List<String> command = new ArrayList<>();
        command.add(runtime.effectiveExecutable());
        command.add("-m");
        command.add(modelPath.toString());
        command.add("--host");
        command.add(runtime.effectiveHost());
        command.add("--port");
        command.add(String.valueOf(runtime.effectivePort()));
        command.add("-c");
        command.add(String.valueOf(contextSize));
        command.add("-np");
        command.add(String.valueOf(runtime.effectiveParallelism()));
        command.add("-a");
        command.add(alias);
        command.add("-t");
        command.add(String.valueOf(threads));
        command.add("-b");
        command.add(String.valueOf(recommended.batchSize()));
        command.add("-ub");
        command.add(String.valueOf(recommended.batchSize()));
        command.add("--cache-type-k");
        command.add(recommended.cacheTypeK());
        command.add("--cache-type-v");
        command.add(recommended.cacheTypeV());

        if (recommended.flashAttn()) { command.add("--flash-attn"); command.add("on"); }

        if (recommended.nGpuLayers() != 0) {
            command.add("--n-gpu-layers");
            command.add(String.valueOf(recommended.nGpuLayers()));
        }

        command.addAll(runtime.effectiveExtraArgs());

        log.info("[orchestrator] Paramètres calculés : threads={} context={} batch={} ngl={} flash={}",
                threads, contextSize, recommended.batchSize(),
                recommended.nGpuLayers(), recommended.flashAttn());

        return command;
    }

    private void waitUntilReady(String alias) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(runtime.effectiveStartupTimeoutSeconds()).toNanos();
        while (System.nanoTime() < deadline) {
            if (chatRuntime == null || !chatRuntime.isAlive()) {
                throw new IllegalStateException("Le process llama-server s'est arrêté avant de devenir prêt");
            }
            try {
                readinessClient.get()
                        .uri("/v1/models")
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block(HEALTH_POLL_INTERVAL);
                return;
            } catch (Exception e) {
                // Reactor encapsule InterruptedException dans une RuntimeException :
                // on restaure le flag et on propage pour sortir de la boucle proprement.
                Throwable cause = e.getCause();
                if (cause instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw (InterruptedException) cause;
                }
                Thread.sleep(HEALTH_POLL_INTERVAL.toMillis());
            }
        }

        throw new IllegalStateException("Timeout de démarrage dépassé pour le modèle " + alias);
    }

    private void streamLogs(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[llama-server] {}", line);
            }
        } catch (Exception e) {
            log.debug("Fin de lecture des logs llama-server: {}", e.getMessage());
        }
    }

    private record RuntimeHandle(String alias, Path modelPath, Process process) {
        boolean isAlive() {
            return process.isAlive();
        }

        long pid() {
            return process.pid();
        }
    }
}
