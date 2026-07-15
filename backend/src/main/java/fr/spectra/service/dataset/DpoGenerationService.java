package fr.spectra.service.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.model.DpoPair;
import fr.spectra.model.TrainingPair;
import fr.spectra.service.LlmChatClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Génère des paires DPO (chosen / rejected) à partir du dataset SFT existant.
 *
 * <p>Pour chaque paire SFT filtrée, le LLM est invité à produire une réponse
 * plausible mais factuellement incorrecte. Ces triplets (prompt, chosen, rejected)
 * sont ensuite utilisés par {@code trl.DPOTrainer} pour apprendre à rejeter les
 * erreurs courantes du domaine.
 *
 * <p>Format de sortie JSONL : {@code {"prompt":"...","chosen":"...","rejected":"...","category":"...","source":"..."}}
 */
@Service
public class DpoGenerationService {

    private static final Logger log = LoggerFactory.getLogger(DpoGenerationService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Seuil de similarité Jaccard au-dessus duquel une paire DPO est rejetée comme trop similaire. */
    private static final double SIMILARITY_THRESHOLD = 0.85;

    private static final String REJECTION_SYSTEM_PROMPT = """
            Tu génères des réponses incorrectes pour l'entraînement DPO (Direct Preference Optimization).
            Pour la question fournie, produis une réponse qui :
            - Semble bien rédigée et professionnelle en apparence
            - Contient une erreur factuelle subtile, une confusion de détails ou une hallucination plausible
            - A une longueur et un style similaires à ce que serait une réponse correcte
            Ne fournis que la réponse erronée, sans préambule ni explication.
            """;

    private final DatasetGeneratorService sftService;
    private final LlmChatClient chatClient;
    private final Path pairsFile;

    private final List<DpoPair> dpoPairs = new CopyOnWriteArrayList<>();
    private final Map<String, DpoTask> tasks = new ConcurrentHashMap<>();
    /** Empêche deux générations DPO concurrentes de tronquer/écraser dpo_pairs.jsonl et la liste partagée. */
    private final java.util.concurrent.atomic.AtomicBoolean generationRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Autowired @Lazy
    private DpoGenerationService self;

    public DpoGenerationService(DatasetGeneratorService sftService,
                                LlmChatClient chatClient,
                                @Value("${spectra.dataset.dir:./data/dataset}") String datasetDir) {
        this.sftService = sftService;
        this.chatClient = chatClient;
        this.pairsFile = Path.of(datasetDir).resolve("dpo_pairs.jsonl");
    }

    @PostConstruct
    private void loadPersistedPairs() {
        if (!Files.exists(pairsFile)) return;
        try (var lines = Files.lines(pairsFile)) {
            lines.forEach(line -> {
                try { dpoPairs.add(mapper.readValue(line, DpoPair.class)); }
                catch (Exception ignored) {}
            });
            log.info("Paires DPO restaurées: {}", dpoPairs.size());
        } catch (Exception e) {
            log.warn("Impossible de charger dpo_pairs.jsonl: {}", e.getMessage());
        }
    }

    public String submit(int maxPairs) {
        if (!generationRunning.compareAndSet(false, true)) {
            return null; // une génération DPO est déjà en cours → l'appelant renvoie 409
        }
        String taskId = UUID.randomUUID().toString();
        tasks.put(taskId, new DpoTask(taskId, "PENDING", 0, 0, null, Instant.now(), null));
        (self != null ? self : this).runAsync(taskId, maxPairs);
        return taskId;
    }

    public List<DpoPair> getAllPairs() {
        return List.copyOf(dpoPairs);
    }

    /**
     * Exporte les paires DPO au format JSONL {@code {prompt, chosen, rejected, ...}} et
     * renvoie le chemin du fichier. C'est le format attendu par {@code trl.DPOTrainer} —
     * à ne pas confondre avec l'export SFT au format {@code conversations}.
     */
    public Path exportJsonl() throws java.io.IOException {
        Files.createDirectories(pairsFile.getParent());
        Path file = pairsFile.getParent().resolve("dpo_export_" + System.currentTimeMillis() + ".jsonl");
        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            for (DpoPair pair : dpoPairs) {
                writer.write(mapper.writeValueAsString(pair));
                writer.newLine();
            }
        }
        log.info("Paires DPO exportées: {} → {}", dpoPairs.size(), file);
        return file;
    }

    public DpoTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * Annulation coopérative d'une génération DPO : le statut passe immédiatement à
     * CANCELLED, la boucle s'arrête à la prochaine paire (les paires déjà générées
     * sont conservées). Même convention que {@code DatasetGeneratorService.cancelTask}.
     *
     * @return {@code false} si la tâche est inconnue ou déjà terminale
     */
    public boolean cancelTask(String taskId) {
        DpoTask task = tasks.get(taskId);
        if (task == null || "COMPLETED".equals(task.status())
                || "FAILED".equals(task.status()) || "CANCELLED".equals(task.status())) {
            return false;
        }
        tasks.put(taskId, new DpoTask(taskId, "CANCELLED", task.processed(), task.total(),
                "Annulé par l'utilisateur", task.startedAt(), Instant.now()));
        return true;
    }

    public List<DpoTask> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    @Async
    protected void runAsync(String taskId, int maxPairs) {
        try {
            generate(taskId, maxPairs);
        } catch (Exception e) {
            log.error("Génération DPO {} échouée: {}", taskId, e.getMessage(), e);
            tasks.computeIfPresent(taskId, (k, t) -> t.failed(e.getMessage()));
        } finally {
            generationRunning.set(false);
        }
    }

    private void generate(String taskId, int maxPairs) throws Exception {
        List<TrainingPair> sftPairs = sftService.getAllPairs().stream()
                .filter(p -> !"negative".equals(p.metadata().category()))
                .toList();

        if (sftPairs.isEmpty()) {
            tasks.computeIfPresent(taskId, (k, t) -> t.failed(
                    "Aucune paire SFT disponible — lancez d'abord POST /api/dataset/generate"));
            return;
        }

        int total = (maxPairs > 0) ? Math.min(maxPairs, sftPairs.size()) : sftPairs.size();
        tasks.computeIfPresent(taskId, (k, t) ->
                new DpoTask(taskId, "RUNNING", 0, total, null, t.startedAt(), null));
        log.info("Génération DPO {}: {} paires à traiter", taskId, total);

        List<DpoPair> localPairs = new ArrayList<>();
        Files.createDirectories(pairsFile.getParent());

        try (BufferedWriter writer = Files.newBufferedWriter(pairsFile,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {

            for (int i = 0; i < total; i++) {
                // Annulation coopérative : on s'arrête à la prochaine paire, les paires
                // déjà écrites dans dpo_pairs.jsonl sont conservées.
                DpoTask current = tasks.get(taskId);
                if (current != null && "CANCELLED".equals(current.status())) {
                    dpoPairs.clear();
                    dpoPairs.addAll(localPairs);
                    log.info("Génération DPO {} annulée après {} paires", taskId, localPairs.size());
                    return;
                }
                TrainingPair sft = sftPairs.get(i);
                DpoPair dpo = generateRejected(sft);
                if (dpo != null) {
                    localPairs.add(dpo);
                    writer.write(mapper.writeValueAsString(dpo));
                    writer.newLine();
                    writer.flush();
                }
                int done = i + 1;
                // Ne pas écraser un statut CANCELLED positionné pendant l'appel LLM.
                tasks.computeIfPresent(taskId, (k, t) -> "CANCELLED".equals(t.status()) ? t
                        : new DpoTask(taskId, "RUNNING", done, total, null, t.startedAt(), null));
            }
        }

        dpoPairs.clear();
        dpoPairs.addAll(localPairs);

        tasks.computeIfPresent(taskId, (k, t) -> "CANCELLED".equals(t.status()) ? t
                : new DpoTask(taskId, "COMPLETED", localPairs.size(), total, null, t.startedAt(), Instant.now()));
        log.info("Génération DPO {} terminée: {} paires", taskId, localPairs.size());
    }

    private DpoPair generateRejected(TrainingPair sft) {
        try {
            String system  = extractRole(sft, "system");
            String user    = extractRole(sft, "user");
            String chosen  = extractRole(sft, "assistant");
            if (user == null || chosen == null) return null;

            // Prompt = system + user combinés (format DPO standard)
            String prompt = (system != null ? system + "\n\n" : "") + user;

            String rejected = chatClient.chat(REJECTION_SYSTEM_PROMPT, "Question : " + user);
            if (rejected == null || rejected.isBlank()) return null;

            String rejectedTrimmed = rejected.trim();
            double similarity = jaccardSimilarity(chosen, rejectedTrimmed);
            if (similarity > SIMILARITY_THRESHOLD) {
                log.warn("Paire DPO ignorée — chosen/rejected trop similaires (Jaccard={}) pour : {}",
                        String.format("%.2f", similarity), user.substring(0, Math.min(60, user.length())));
                return null;
            }

            return new DpoPair(prompt, chosen, rejectedTrimmed,
                    sft.metadata().category(), sft.metadata().source());
        } catch (Exception e) {
            log.warn("Échec génération rejet DPO: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Similarité de Jaccard sur ensembles de mots pour détecter les paires chosen/rejected trop proches.
     */
    private double jaccardSimilarity(String a, String b) {
        Set<String> setA = Arrays.stream(a.toLowerCase().split("\\s+")).collect(Collectors.toSet());
        Set<String> setB = Arrays.stream(b.toLowerCase().split("\\s+")).collect(Collectors.toSet());
        if (setA.isEmpty() && setB.isEmpty()) return 1.0;
        long intersection = setA.stream().filter(setB::contains).count();
        long union = setA.size() + setB.size() - intersection;
        return union == 0 ? 1.0 : (double) intersection / union;
    }

    private String extractRole(TrainingPair pair, String role) {
        return pair.conversations().stream()
                .filter(m -> role.equals(m.role()))
                .map(TrainingPair.Message::content)
                .findFirst().orElse(null);
    }

    // ── Task DTO ─────────────────────────────────────────────────────────────

    public record DpoTask(
            String taskId,
            String status,
            int processed,
            int total,
            String error,
            Instant startedAt,
            Instant completedAt
    ) {
        public DpoTask failed(String error) {
            return new DpoTask(taskId, "FAILED", processed, total, error, startedAt, Instant.now());
        }
    }
}
