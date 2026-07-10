package fr.spectra.service;

import fr.spectra.dto.ResourceProfile;
import fr.spectra.dto.ResourceProfile.LlamaServerParams;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Détecte les ressources disponibles (CPU, RAM, GPU) et calcule les paramètres
 * optimaux pour llama-server.
 *
 * <p>La détection est effectuée une seule fois au démarrage de l'application
 * (résultat mis en cache). Les recommandations sont exposées via
 * {@code GET /api/config/resources} et intégrées dans
 * {@link LlamaCppRuntimeOrchestrator} pour le mode runtime embarqué.
 *
 * <p>Sources de détection (par ordre de priorité) :
 * <ol>
 *   <li>Quotas cgroups v2 ({@code /sys/fs/cgroup/}) — valeurs container-aware</li>
 *   <li>{@code /proc/meminfo} et {@code Runtime.availableProcessors()} — valeurs hôte</li>
 *   <li>{@code nvidia-smi} — GPU NVIDIA</li>
 *   <li>Présence de {@code /dev/kfd} — GPU AMD ROCm</li>
 *   <li>Présence de {@code /dev/dri/renderD128} — GPU Vulkan générique</li>
 * </ol>
 */
@Service
public class ResourceAdvisorService {

    private static final Logger log = LoggerFactory.getLogger(ResourceAdvisorService.class);

    private volatile ResourceProfile cachedProfile;

    @PostConstruct
    void init() {
        cachedProfile = detect();
        logProfile(cachedProfile);
    }

    /** Retourne le profil détecté au démarrage (résultat mis en cache). */
    public ResourceProfile getProfile() {
        return cachedProfile;
    }

    /** Recalcule le profil en temps réel (utile après changement de ressources). */
    public ResourceProfile refresh() {
        cachedProfile = detect();
        logProfile(cachedProfile);
        return cachedProfile;
    }

    // ── Détection ──────────────────────────────────────────────────────────────

    ResourceProfile detect() {
        int cpuCores = detectCpuCores();
        long ramMb = detectRamMb();
        boolean cgroupCpuActive = isCgroupCpuQuotaActive();
        boolean cgroupMemActive = isCgroupMemoryLimitActive();

        String gpuType = detectGpuType();
        long gpuVramMb = "nvidia".equals(gpuType) ? detectNvidiaVramMb() : 0L;

        LlamaServerParams chat  = recommend(cpuCores, ramMb, gpuType, gpuVramMb, "chat");
        LlamaServerParams embed = recommend(cpuCores, ramMb, gpuType, gpuVramMb, "embed");

        return new ResourceProfile(cpuCores, ramMb, gpuType, gpuVramMb,
                cgroupMemActive, cgroupCpuActive, chat, embed);
    }

    private int detectCpuCores() {
        int jvmCores = Runtime.getRuntime().availableProcessors();

        // cgroups v2 : quota CPU (cpu.max = "quota period")
        try {
            String cpuMax = Files.readString(Path.of("/sys/fs/cgroup/cpu.max")).trim();
            if (!cpuMax.startsWith("max")) {
                String[] parts = cpuMax.split("\\s+");
                long quota  = Long.parseLong(parts[0]);
                long period = Long.parseLong(parts[1]);
                int cgroupCores = (int) (quota / period);
                return Math.min(jvmCores, Math.max(1, cgroupCores));
            }
        } catch (Exception ignored) { /* pas de cgroup v2 ou hôte Windows */ }

        return jvmCores;
    }

    private long detectRamMb() {
        long ramMb = 0;

        // /proc/meminfo (Linux)
        try {
            Pattern p = Pattern.compile("MemAvailable:\\s+(\\d+)\\s+kB");
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                Matcher m = p.matcher(line);
                if (m.find()) {
                    ramMb = Long.parseLong(m.group(1)) / 1024;
                    break;
                }
            }
        } catch (Exception ignored) { /* Windows ou /proc absent */ }

        // Fallback JVM
        if (ramMb == 0) {
            ramMb = Runtime.getRuntime().maxMemory() / 1024 / 1024;
        }

        // cgroups v2 : limite mémoire container
        try {
            String memMax = Files.readString(Path.of("/sys/fs/cgroup/memory.max")).trim();
            if (!memMax.equals("max")) {
                long cgroupMb = Long.parseLong(memMax) / 1024 / 1024;
                ramMb = Math.min(ramMb, cgroupMb);
            }
        } catch (Exception ignored) {}

        return ramMb;
    }

    private boolean isCgroupCpuQuotaActive() {
        try {
            String v = Files.readString(Path.of("/sys/fs/cgroup/cpu.max")).trim();
            return !v.startsWith("max");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isCgroupMemoryLimitActive() {
        try {
            String v = Files.readString(Path.of("/sys/fs/cgroup/memory.max")).trim();
            return !v.equals("max");
        } catch (Exception e) {
            return false;
        }
    }

    private String detectGpuType() {
        // NVIDIA : nvidia-smi
        try {
            Process p = new ProcessBuilder("nvidia-smi", "--query-gpu=name",
                    "--format=csv,noheader")
                    .redirectErrorStream(true)
                    .start();
            boolean done = p.waitFor(3, TimeUnit.SECONDS);
            if (done && p.exitValue() == 0) {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line = r.readLine();
                    if (line != null && !line.isBlank()) return "nvidia";
                }
            }
        } catch (Exception ignored) {}

        // AMD ROCm
        if (Files.exists(Path.of("/dev/kfd"))) return "amd";

        // Vulkan générique
        if (Files.exists(Path.of("/dev/dri/renderD128"))) return "vulkan";

        return "none";
    }

    private long detectNvidiaVramMb() {
        try {
            Process p = new ProcessBuilder("nvidia-smi",
                    "--query-gpu=memory.total", "--format=csv,noheader,nounits")
                    .redirectErrorStream(true)
                    .start();
            boolean done = p.waitFor(3, TimeUnit.SECONDS);
            if (done && p.exitValue() == 0) {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line = r.readLine();
                    if (line != null) return Long.parseLong(line.trim());
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    // ── Calcul des recommandations ─────────────────────────────────────────────

    LlamaServerParams recommend(int cpuCores, long ramMb,
                                String gpuType, long gpuVramMb,
                                String mode) {
        boolean hasGpu = !"none".equals(gpuType);

        // Threads
        int threads;
        if ("embed".equals(mode)) {
            threads = Math.max(1, cpuCores / 2);
        } else {
            threads = cpuCores > 4 ? cpuCores - 2 : Math.max(1, cpuCores);
        }

        // Contexte
        int contextSize;
        if ("nvidia".equals(gpuType) && gpuVramMb >= 8192) {
            contextSize = 8192;
        } else if ("nvidia".equals(gpuType) && gpuVramMb >= 4096) {
            contextSize = 4096;
        } else if (ramMb >= 32768) {
            contextSize = 4096;
        } else if (ramMb >= 16384) {
            contextSize = 2048;
        } else if (ramMb >= 8192) {
            contextSize = 1024;
        } else {
            contextSize = 512;
        }

        // Batch
        int batchSize = (hasGpu || ramMb >= 16384) ? 2048
                : ramMb >= 8192 ? 1024 : 512;

        // GPU layers
        int nGpuLayers = switch (gpuType) {
            case "nvidia", "amd" -> -1;   // tout offloader
            case "vulkan"        -> 20;   // offload partiel, conservateur
            default              -> 0;
        };

        // Flash attention : activée par défaut
        boolean flashAttn = true;

        // KV cache : Q8 par défaut, IQ si GPU avec VRAM abondante (turboquant)
        String cacheTypeK = (hasGpu && gpuVramMb >= 8192) ? "iq4_nl" : "q8_0";
        String cacheTypeV = "q8_0";

        List<String> cli = buildCli(threads, contextSize, batchSize, nGpuLayers,
                flashAttn, cacheTypeK, cacheTypeV, "embed".equals(mode));

        return new LlamaServerParams(threads, contextSize, batchSize, nGpuLayers,
                flashAttn, cacheTypeK, cacheTypeV, cli);
    }

    private List<String> buildCli(int threads, int contextSize, int batchSize,
                                  int nGpuLayers, boolean flashAttn,
                                  String cacheTypeK, String cacheTypeV,
                                  boolean embedMode) {
        List<String> args = new ArrayList<>(List.of(
                "-t", String.valueOf(threads),
                "-c", String.valueOf(contextSize),
                "-b", String.valueOf(batchSize),
                "-ub", String.valueOf(batchSize),
                "--cache-type-k", cacheTypeK,
                "--cache-type-v", cacheTypeV
        ));
        if (flashAttn)   args.add("--flash-attn");
        if (embedMode)   args.add("--embeddings");
        if (nGpuLayers != 0) {
            args.add("--n-gpu-layers");
            args.add(String.valueOf(nGpuLayers));
        }
        return List.copyOf(args);
    }

    // ── Logging ────────────────────────────────────────────────────────────────

    private void logProfile(ResourceProfile p) {
        log.info("[resources] ══════════════════════════════════════════");
        log.info("[resources]  CPU cœurs     : {} (cgroup quota: {})",
                p.cpuCores(), p.cgroupCpuQuotaActive() ? "oui" : "non");
        log.info("[resources]  RAM disponible: {} MB (cgroup limit: {})",
                p.ramMb(), p.cgroupMemoryLimitActive() ? "oui" : "non");
        log.info("[resources]  GPU           : {} (VRAM: {} MB)", p.gpuType(), p.gpuVramMb());
        log.info("[resources] ── Recommandations chat ─────────────────");
        logParams(p.chatRecommendation());
        log.info("[resources] ── Recommandations embed ────────────────");
        logParams(p.embedRecommendation());
        log.info("[resources] ══════════════════════════════════════════");
    }

    private void logParams(LlamaServerParams r) {
        log.info("[resources]   threads={} context={} batch={} ngl={} flash={} KV={}/{}",
                r.threads(), r.contextSize(), r.batchSize(), r.nGpuLayers(),
                r.flashAttn(), r.cacheTypeK(), r.cacheTypeV());
        log.info("[resources]   CLI: {}", String.join(" ", r.cliArgs()));
    }
}
