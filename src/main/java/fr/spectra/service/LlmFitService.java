package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.dto.LlmFitRecommendation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
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
    private static final Pattern GGUF_PATH_PATTERN = Pattern.compile("([^\\s]+\\.gguf)");

    private final ObjectMapper objectMapper;
    private final ModelRegistryService modelRegistryService;
    private final Map<String, Sinks.Many<InstallationProgress>> progressSinks = new ConcurrentHashMap<>();
    private final Map<String, InstallationProgress> progressSnapshots = new ConcurrentHashMap<>();

    @Value("${llmfit.path:llmfit}")
    private String llmfitPath;

    public LlmFitService(ObjectMapper objectMapper, ModelRegistryService modelRegistryService) {
        this.objectMapper = objectMapper;
        this.modelRegistryService = modelRegistryService;
    }

    public LlmFitRecommendation getRecommendations(int limit, String memory, String ram, Integer cpuCores) {
        try {
            // Ask for more models than requested to allow filtering non-GGUF ones
            int fetchLimit = Math.max(limit * 3, 30);
            List<String> command = new ArrayList<>(List.of(llmfitPath, "recommend", "--json", "--limit", String.valueOf(fetchLimit)));
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
                LlmFitRecommendation raw = objectMapper.readValue(reader, LlmFitRecommendation.class);
                if (raw == null || raw.models() == null) {
                    return raw;
                }

                // Filter for GGUF compatible models and set downloadRepo
                List<LlmFitRecommendation.ModelRecommendation> filteredModels = raw.models().stream()
                        .filter(this::isGgufCompatible)
                        .map(m -> {
                            String downloadRepo = m.name();
                            if (m.ggufSources() != null && !m.ggufSources().isEmpty()) {
                                // If main name doesn't look like GGUF, use first GGUF source
                                if (downloadRepo == null || !downloadRepo.toUpperCase().contains("GGUF")) {
                                    downloadRepo = m.ggufSources().get(0).repo();
                                }
                            }
                            return new LlmFitRecommendation.ModelRecommendation(
                                    m.name(),
                                    downloadRepo,
                                    m.provider(),
                                    m.parameterCount(),
                                    m.useCase(),
                                    m.score(),
                                    m.fitLevel(),
                                    m.bestQuant(),
                                    m.estimatedTps(),
                                    m.memoryRequiredGb(),
                                    m.diskSizeGb(),
                                    m.contextLength(),
                                    m.notes(),
                                    m.scoreComponents(),
                                    m.ggufSources(),
                                    m.installed(),
                                    m.runMode()
                            );
                        })
                        .limit(limit)
                        .toList();

                return new LlmFitRecommendation(filteredModels, raw.system());
            }
        } catch (Exception e) {
            log.error("Erreur lors de la récupération des recommandations llmfit", e);
            return new LlmFitRecommendation(Collections.emptyList(), null);
        }
    }

    private boolean isGgufCompatible(LlmFitRecommendation.ModelRecommendation model) {
        if (model == null) return false;

        // 1. If we have explicit GGUF sources, it's compatible
        if (model.ggufSources() != null && !model.ggufSources().isEmpty()) {
            return true;
        }

        // 2. Check for known non-GGUF suffixes in the repository name.
        // If the name points to a GPTQ, AWQ, or EXL2 repo and no GGUF sources are provided, reject it.
        if (model.name() != null) {
            String name = model.name().toUpperCase();
            if (name.contains(".W4A16") || name.contains("-GPTQ") || name.contains("-AWQ") || name.contains("-EXL2")) {
                return false;
            }
        }

        // 3. Check if name contains GGUF (case insensitive)
        // Most GGUF repositories on HuggingFace have "GGUF" in their name.
        // If a model has no GGUF sources and no "GGUF" in its name, it's likely a base repo (Safetensors)
        // that llmfit might recommend theoretically but cannot download as GGUF.
        if (model.name() != null && model.name().toUpperCase().contains("GGUF")) {
            return true;
        }

        // 4. Fallback: if it doesn't have GGUF in name and no sources, we don't trust it
        // even if best_quant looks like a GGUF quant (e.g. Q4_K_M).
        return false;
    }

    public Flux<InstallationProgress> getInstallationProgress(String modelName) {
        InstallationProgress snapshot = progressSnapshots.get(modelName);
        if (snapshot != null && snapshot.isTerminal()) {
            return Flux.just(snapshot);
        }

        Sinks.Many<InstallationProgress> sink = progressSinks.computeIfAbsent(modelName,
                k -> Sinks.many().replay().latest());

        if (snapshot != null) {
            sink.tryEmitNext(snapshot);
        }

        return sink.asFlux();
    }

    public CompletableFuture<Boolean> installModel(String modelName, String quant, boolean autoActivate) {
        Sinks.Many<InstallationProgress> sink = progressSinks.computeIfAbsent(modelName,
                k -> Sinks.many().replay().latest());
        emitProgress(modelName, sink, 0, "RUNNING", "Téléchargement démarré");

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

                Deque<String> lastOutputLines = new ArrayDeque<>();
                final String[] downloadedFileRef = new String[1];
                CompletableFuture<Void> outputReader = CompletableFuture.runAsync(() -> {
                    try {
                        readProcessOutput(process, line -> {
                            log.debug("[llmfit] {}", line);
                            rememberLine(lastOutputLines, line);

                            Matcher progressMatcher = PROGRESS_PATTERN.matcher(line);
                            if (progressMatcher.find()) {
                                try {
                                    int progress = Math.min(99, (int) Double.parseDouble(progressMatcher.group(1)));
                                    emitProgress(modelName, sink, progress, "RUNNING", "Téléchargement en cours");
                                } catch (Exception ignored) {
                                    // Ignore malformed progress fragments from CLI output.
                                }
                            }

                            Matcher pathMatcher = GGUF_PATH_PATTERN.matcher(line);
                            while (pathMatcher.find()) {
                                downloadedFileRef[0] = pathMatcher.group(1);
                            }
                        });
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                boolean finished = process.waitFor(60, TimeUnit.MINUTES);
                if (!finished) {
                    process.destroyForcibly();
                    log.error("Timeout installation du modèle {} — process tué", modelName);
                    emitProgress(modelName, sink, 0, "FAILED", "Timeout après 60 minutes");
                    sink.tryEmitComplete();
                    return false;
                }
                outputReader.get(5, TimeUnit.SECONDS);
                String downloadedFile = downloadedFileRef[0];

                if (process.exitValue() == 0) {
                    log.info("Modèle {} installé avec succès", modelName);
                    emitProgress(modelName, sink, 100, "COMPLETED", "Modèle installé");
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
                    String detail = String.join(" | ", lastOutputLines);
                    String message = detail.isBlank()
                            ? "llmfit a quitté avec le code " + process.exitValue()
                            : "llmfit a quitté avec le code " + process.exitValue() + " : " + detail;
                    log.error("Échec de l'installation du modèle {} (exit code: {}). Dernières sorties: {}",
                            modelName, process.exitValue(), detail);
                    emitProgress(modelName, sink, 0, "FAILED", message);
                    sink.tryEmitComplete();
                    return false;
                }
            } catch (Exception e) {
                log.error("Erreur lors de l'installation du modèle " + modelName, e);
                emitProgress(modelName, sink, 0, "FAILED", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                sink.tryEmitComplete();
                return false;
            } finally {
                progressSinks.remove(modelName);
            }
        });
    }

    private void readProcessOutput(Process process, java.util.function.Consumer<String> lineConsumer) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(process.getInputStream())) {
            StringBuilder currentLine = new StringBuilder();
            int next;
            while ((next = reader.read()) != -1) {
                char ch = (char) next;
                if (ch == '\n' || ch == '\r') {
                    flushLine(currentLine, lineConsumer);
                } else {
                    currentLine.append(ch);
                }
            }
            flushLine(currentLine, lineConsumer);
        }
    }

    private void flushLine(StringBuilder currentLine, java.util.function.Consumer<String> lineConsumer) {
        if (currentLine.isEmpty()) {
            return;
        }
        lineConsumer.accept(currentLine.toString().trim());
        currentLine.setLength(0);
    }

    private void rememberLine(Deque<String> lines, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        lines.addLast(line);
        while (lines.size() > 8) {
            lines.removeFirst();
        }
    }

    private void emitProgress(String modelName,
                              Sinks.Many<InstallationProgress> sink,
                              int progress,
                              String status,
                              String message) {
        InstallationProgress event = new InstallationProgress(progress, status, message);
        progressSnapshots.put(modelName, event);
        sink.tryEmitNext(event);
    }

    public record InstallationProgress(int progress, String status, String message) {
        public boolean isTerminal() {
            return "COMPLETED".equals(status) || "FAILED".equals(status);
        }
    }
}
