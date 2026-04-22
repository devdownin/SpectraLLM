package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.dto.LlmFitRecommendation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Service
public class LlmFitService {

    private static final Logger log = LoggerFactory.getLogger(LlmFitService.class);
    private static final Pattern PROGRESS_PATTERN = Pattern.compile("(\\d+(\\.\\d+)?)%");

    private final ObjectMapper objectMapper;
    private final ModelRegistryService modelRegistryService;
    private final Map<String, Sinks.Many<Integer>> progressSinks = new ConcurrentHashMap<>();

    @Value("${llmfit.path:llmfit}")
    private String llmfitPath;

    public LlmFitService(ObjectMapper objectMapper, ModelRegistryService modelRegistryService) {
        this.objectMapper = objectMapper;
        this.modelRegistryService = modelRegistryService;
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
        Sinks.Many<Integer> sink = progressSinks.computeIfAbsent(modelName,
                k -> Sinks.many().replay().latest());

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

                        if (line.contains("Downloaded") && line.contains(".gguf")) {
                             int start = line.lastIndexOf(" ") + 1;
                             downloadedFile = line.substring(start).trim();
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
                        Path path = Path.of(downloadedFile);
                        String fileName = path.getFileName().toString();
                        String alias = fileName.replace(".gguf", "");

                        modelRegistryService.registerChatModel(
                            alias,
                            downloadedFile,
                            "Tu es un assistant IA spécialisé.",
                            Collections.emptyMap(),
                            "llmfit"
                        );
                        log.info("Modèle enregistré dans Spectra sous l'alias : {}", alias);

                        if (autoActivate) {
                            modelRegistryService.setActiveChatModel(alias);
                            log.info("Modèle '{}' activé automatiquement.", alias);
                        }
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
            } finally {
                progressSinks.remove(modelName);
            }
        });
    }
}
