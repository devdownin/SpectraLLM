package fr.spectra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import fr.spectra.persistence.IngestedFileEntity;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.util.LlmJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * Brouillon de benchmark tiré du corpus, pour amorcer la calibration sur VOS documents.
 *
 * <p>Un passage est tiré par document, à tour de rôle entre les documents (un gros document ne
 * monopolise pas le jeu) ; le LLM en tire une question et la réponse attendue, et le passage
 * donne {@code expectedSources}. Le fichier est un <b>brouillon</b> : questions trop proches du
 * texte, réponses approximatives et passages sans intérêt sont à corriger ou retirer à la main, et
 * des questions hors corpus ({@code "answerable": false}) à ajouter — le générateur n'en produit
 * pas, faute de pouvoir prouver qu'une réponse est absente du corpus.</p>
 */
@Service
public class BenchmarkDraftService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkDraftService.class);

    public static final int MAX_SIZE = 60;
    private static final int MIN_PASSAGE_CHARS = 200;
    private static final int MAX_PASSAGE_CHARS = 2500;
    private static final int SCAN_LIMIT = 5000;
    private static final int PAGE = 500;

    static final String SYSTEM_PROMPT = """
            Tu prépares un jeu d'évaluation pour un assistant documentaire.
            À partir du passage fourni, écris UNE question qu'un utilisateur poserait et à laquelle ce
            passage répond. Formule-la avec tes propres mots, sans reprendre les phrases du passage :
            l'évaluation doit mesurer la recherche, pas la recopie. Puis écris la réponse attendue,
            courte et tirée du passage seul.
            Réponds uniquement en JSON : {"question": "...", "reference": "..."}
            Si le passage ne permet aucune question factuelle (sommaire, mentions légales, liste vide),
            réponds {"question": ""}.""";

    public record DraftItem(String question, String reference, String category, boolean answerable,
                            List<String> expectedSources, boolean draft) {}

    public record DraftResult(String file, int scannedChunks, List<DraftItem> items) {}

    public enum JobStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

    /** Instantané d'un brouillon asynchrone ; {@code items} se remplit au fil de la génération. */
    public record DraftJob(String jobId, JobStatus status, int total, int processed, int scannedChunks,
                           String file, List<DraftItem> items, String error, Instant createdAt, Instant completedAt) {}

    private static final int MAX_JOBS = 10;

    /** État mutable d'un job ; seul l'instantané {@link DraftJob} sort du service. */
    private static final class JobState {
        final String jobId = UUID.randomUUID().toString();
        final Instant createdAt = Instant.now();
        volatile JobStatus status = JobStatus.PENDING;
        volatile int total;
        volatile int processed;
        volatile int scannedChunks;
        volatile String file;
        volatile String error;
        volatile Instant completedAt;
        volatile boolean cancelRequested;
        final List<DraftItem> items = java.util.Collections.synchronizedList(new ArrayList<>());

        DraftJob snapshot() {
            synchronized (items) {
                return new DraftJob(jobId, status, total, processed, scannedChunks, file,
                        List.copyOf(items), error, createdAt, completedAt);
            }
        }
    }

    private final ChromaDbClient chromaDbClient;
    private final LlmChatClient llm;
    private final IngestedFileRepository files;
    private final SpectraProperties props;
    private final Path outputDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, JobState> jobs = new LinkedHashMap<>();
    // Un seul brouillon à la fois : chaque question coûte un appel LLM, et deux jobs se disputeraient le modèle.
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "benchmark-draft");
        t.setDaemon(true);
        return t;
    });

    public BenchmarkDraftService(ChromaDbClient chromaDbClient, LlmChatClient llm, IngestedFileRepository files,
                                 SpectraProperties props,
                                 @Value("${spectra.benchmark.draft-dir:./data/benchmarks}") String outputDir) {
        this.chromaDbClient = chromaDbClient;
        this.llm = llm;
        this.files = files;
        this.props = props;
        this.outputDir = Path.of(outputDir);
    }

    /**
     * Génère jusqu'à {@code size} questions et les écrit en JSONL dans {@code spectra.benchmark.draft-dir}.
     * Un appel LLM par question : compter quelques secondes chacune sur CPU — au-delà de quelques
     * questions, préférer {@link #submit}, car une requête HTTP bloquante dépasse le délai du proxy.
     */
    public DraftResult draft(String collection, int size) throws IOException {
        List<DraftItem> items = new ArrayList<>();
        int scanned = generate(collection, size, items, n -> { }, () -> false, c -> { });
        Path file = write(items);
        return new DraftResult(file.toString(), scanned, List.copyOf(items));
    }

    /**
     * Lance un brouillon en tâche de fond.
     *
     * @return l'identifiant du job, ou {@code null} si un brouillon est déjà en cours
     */
    public synchronized String submit(String collection, int size) {
        if (jobs.values().stream().anyMatch(j -> j.status == JobStatus.PENDING || j.status == JobStatus.RUNNING)) {
            return null;
        }
        JobState job = new JobState();
        job.total = Math.max(1, Math.min(MAX_SIZE, size));
        jobs.put(job.jobId, job);
        while (jobs.size() > MAX_JOBS) jobs.remove(jobs.keySet().iterator().next());
        executor.submit(() -> run(job, collection));
        return job.jobId;
    }

    public synchronized DraftJob getJob(String jobId) {
        JobState job = jobs.get(jobId);
        return job != null ? job.snapshot() : null;
    }

    /** Annulation coopérative, prise en compte entre deux questions ; les questions déjà produites restent. */
    public synchronized boolean cancel(String jobId) {
        JobState job = jobs.get(jobId);
        if (job == null || (job.status != JobStatus.PENDING && job.status != JobStatus.RUNNING)) return false;
        job.cancelRequested = true;
        return true;
    }

    private void run(JobState job, String collection) {
        job.status = JobStatus.RUNNING;
        try {
            job.scannedChunks = generate(collection, job.total, job.items, n -> job.processed = n,
                    () -> job.cancelRequested, c -> job.scannedChunks = c);
            job.file = write(job.snapshot().items()).toString();
            job.status = job.cancelRequested ? JobStatus.CANCELLED : JobStatus.COMPLETED;
        } catch (Exception e) {
            log.warn("Brouillon de benchmark en échec : {}", e.getMessage());
            job.error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            job.status = JobStatus.FAILED;
        } finally {
            job.completedAt = Instant.now();
        }
    }

    /** @return le nombre de chunks parcourus */
    private int generate(String collection, int size, List<DraftItem> items, IntConsumer onProgress,
                         BooleanSupplier cancelled, IntConsumer onScanned) throws IOException {
        int target = Math.max(1, Math.min(MAX_SIZE, size));
        String collectionName = collection != null && !collection.isBlank() ? collection
                : props.chromadb() != null ? props.chromadb().effectiveCollection() : "spectra_documents";
        String collectionId = chromaDbClient.getOrCreateCollection(collectionName);

        Map<String, Deque<Passage>> bySource = new LinkedHashMap<>();
        int scanned = scan(collectionId, bySource);
        onScanned.accept(scanned);
        Map<String, String> categoryBySha = categories();

        int attempted = 0;
        while (items.size() < target && !cancelled.getAsBoolean()
                && bySource.values().stream().anyMatch(q -> !q.isEmpty())) {
            for (Deque<Passage> queue : bySource.values()) {
                if (items.size() >= target || cancelled.getAsBoolean()) break;
                Passage passage = queue.poll();
                if (passage == null) continue;
                question(passage).ifPresent(q -> items.add(new DraftItem(q.question(), q.reference(),
                        categoryBySha.getOrDefault(passage.sha256(), "general"), true,
                        List.of(passage.sourceFile()), true)));
                onProgress.accept(items.size());
                attempted++;
            }
        }
        log.info("Brouillon de benchmark : {} question(s) depuis {} chunk(s) de '{}' ({} passage(s) essayé(s))",
                items.size(), scanned, collectionName, attempted);
        return scanned;
    }

    private record Passage(String text, String sourceFile, String sha256) {}

    private record Generated(String question, String reference) {}

    /** Passages exploitables groupés par source, dans un ordre mélangé mais reproductible. */
    @SuppressWarnings("unchecked")
    private int scan(String collectionId, Map<String, Deque<Passage>> bySource) {
        Map<String, List<Passage>> grouped = new LinkedHashMap<>();
        int scanned = 0;
        for (int offset = 0; offset < SCAN_LIMIT; offset += PAGE) {
            Map<String, Object> page = chromaDbClient.getDocumentsPaged(collectionId, PAGE, offset);
            List<?> docs = page != null && page.get("documents") instanceof List<?> d ? d : List.of();
            List<?> metas = page != null && page.get("metadatas") instanceof List<?> m ? m : List.of();
            if (docs.isEmpty()) break;
            for (int i = 0; i < docs.size(); i++) {
                scanned++;
                if (!(docs.get(i) instanceof String text) || text.strip().length() < MIN_PASSAGE_CHARS) continue;
                Map<String, Object> meta = i < metas.size() && metas.get(i) instanceof Map<?, ?> mm
                        ? (Map<String, Object>) mm : Map.of();
                String source = String.valueOf(meta.getOrDefault("sourceFile", "inconnu"));
                Object sha = meta.get("sha256");
                grouped.computeIfAbsent(source, k -> new ArrayList<>())
                        .add(new Passage(text.strip(), source, sha != null ? sha.toString() : null));
            }
            if (docs.size() < PAGE) break;
        }
        Random random = new Random(42);
        grouped.forEach((source, passages) -> {
            Collections.shuffle(passages, random);
            bySource.put(source, new ArrayDeque<>(passages));
        });
        return scanned;
    }

    private Map<String, String> categories() {
        Map<String, String> bySha = new LinkedHashMap<>();
        for (IngestedFileEntity f : files.findAllByOrderByIngestedAtDesc()) {
            if (f.getSha256() != null && f.getCategories() != null && !f.getCategories().isEmpty()) {
                bySha.putIfAbsent(f.getSha256(), f.getCategories().getFirst());
            }
        }
        return bySha;
    }

    private java.util.Optional<Generated> question(Passage passage) {
        String text = passage.text().length() > MAX_PASSAGE_CHARS
                ? passage.text().substring(0, MAX_PASSAGE_CHARS) : passage.text();
        try {
            String json = LlmJson.extract(llm.chatJson(SYSTEM_PROMPT,
                    "Passage (source : " + passage.sourceFile() + ") :\n" + text, 0.3f, 0.9f));
            if (json == null) return java.util.Optional.empty();
            JsonNode node = mapper.readTree(json);
            String question = node.path("question").asText("").strip();
            String reference = node.path("reference").asText("").strip();
            return question.isEmpty() || reference.isEmpty() ? java.util.Optional.empty()
                    : java.util.Optional.of(new Generated(question, reference));
        } catch (Exception e) {
            log.debug("Brouillon de benchmark : passage de '{}' ignoré ({})", passage.sourceFile(), e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private Path write(List<DraftItem> items) throws IOException {
        Files.createDirectories(outputDir);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        Path file = outputDir.resolve("draft-" + stamp + ".jsonl");
        StringBuilder jsonl = new StringBuilder();
        for (DraftItem item : items) jsonl.append(mapper.writeValueAsString(item)).append('\n');
        Files.writeString(file, jsonl.toString(), StandardCharsets.UTF_8);
        return file;
    }
}
