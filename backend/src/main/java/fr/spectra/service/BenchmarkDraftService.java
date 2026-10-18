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

    private final ChromaDbClient chromaDbClient;
    private final LlmChatClient llm;
    private final IngestedFileRepository files;
    private final SpectraProperties props;
    private final Path outputDir;
    private final ObjectMapper mapper = new ObjectMapper();

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
     * Un appel LLM par question : compter quelques secondes chacune sur CPU.
     */
    public DraftResult draft(String collection, int size) throws IOException {
        int target = Math.max(1, Math.min(MAX_SIZE, size));
        String collectionName = collection != null && !collection.isBlank() ? collection
                : props.chromadb() != null ? props.chromadb().effectiveCollection() : "spectra_documents";
        String collectionId = chromaDbClient.getOrCreateCollection(collectionName);

        Map<String, Deque<Passage>> bySource = new LinkedHashMap<>();
        int scanned = scan(collectionId, bySource);
        Map<String, String> categoryBySha = categories();

        List<DraftItem> items = new ArrayList<>();
        while (items.size() < target && bySource.values().stream().anyMatch(q -> !q.isEmpty())) {
            for (Deque<Passage> queue : bySource.values()) {
                if (items.size() >= target) break;
                Passage passage = queue.poll();
                if (passage == null) continue;
                question(passage).ifPresent(q -> items.add(new DraftItem(q.question(), q.reference(),
                        categoryBySha.getOrDefault(passage.sha256(), "general"), true,
                        List.of(passage.sourceFile()), true)));
            }
        }
        Path file = write(items);
        log.info("Brouillon de benchmark : {} question(s) depuis {} chunk(s) de '{}' → {}",
                items.size(), scanned, collectionName, file);
        return new DraftResult(file.toString(), scanned, items);
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
