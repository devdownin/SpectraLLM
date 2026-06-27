package fr.spectra.service.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.model.TrainingPair;
import fr.spectra.service.ChromaDbClient;
import fr.spectra.service.LlmChatClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

/**
 * Génération de dataset synthétique — le carburant du fine-tuning.
 *
 * <p><b>Pourquoi générer un dataset ?</b> Pour spécialiser un modèle par fine-tuning, il faut
 * des milliers d'exemples question/réponse de votre domaine. Les annoter à la main est hors de
 * portée. L'astuce de Spectra : <i>réutiliser vos propres documents</i> déjà ingérés. On
 * présente chaque chunk au LLM en lui demandant de fabriquer des paires Q/R ancrées dans ce
 * texte — c'est l'approche « auto-instruct » / distillation : le modèle génère les données qui
 * serviront ensuite à l'entraîner. Cela ferme la boucle <b>RAG → dataset → fine-tuning</b>.</p>
 *
 * <p><b>Comment.</b> Pour chaque chunk tiré de ChromaDB ({@link ChromaDbClient}), le
 * {@link LlmChatClient} produit des {@link TrainingPair} (question, réponse, catégorie, type,
 * score de confiance). Les paires sont accumulées en mémoire et <b>persistées au fil de l'eau</b>
 * en JSONL ({@code sft_pairs.jsonl}) — donc rechargées au redémarrage ({@code @PostConstruct}),
 * pour ne jamais reperdre une génération longue.</p>
 *
 * <p><b>Exécution.</b> La génération est asynchrone ({@code @Async}) avec suivi de progression
 * par tâche, annulation ({@code cancelledTasks}) et un verrou {@code generationRunning} qui
 * empêche deux générations simultanées. Le champ {@code self} (auto-référence {@code @Lazy})
 * permet d'invoquer la méthode {@code @Async} à travers le proxy Spring sans dépendance
 * circulaire.</p>
 */
@Service
public class DatasetGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(DatasetGeneratorService.class);
    private static final String COLLECTION_NAME = "spectra_documents";
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final Set<String> VALID_CATEGORIES =
            Set.of("procedures", "evenements", "nomenclatures", "reglementation");

    private final LlmChatClient llmChatClient;
    private final ChromaDbClient chromaDbClient;
    private final Path pairsFile;

    /** Self-reference for @Async proxy — injected lazily to avoid circular dependency. */
    @Lazy @Autowired(required = false)
    private DatasetGeneratorService self;

    private final List<TrainingPair> generatedPairs = new CopyOnWriteArrayList<>();
    private final Map<String, GenerationTask> tasks = new ConcurrentHashMap<>();
    private final AtomicBoolean generationRunning = new AtomicBoolean(false);
    private final Set<String> cancelledTasks = ConcurrentHashMap.newKeySet();

    public DatasetGeneratorService(LlmChatClient llmChatClient,
                                   ChromaDbClient chromaDbClient,
                                   @Value("${spectra.dataset.dir:./data/dataset}") String datasetDir) {
        this.llmChatClient = llmChatClient;
        this.chromaDbClient = chromaDbClient;
        this.pairsFile = Path.of(datasetDir).resolve("sft_pairs.jsonl");
    }

    @PostConstruct
    private void loadPersistedPairs() {
        if (!Files.exists(pairsFile)) return;
        try (var lines = Files.lines(pairsFile)) {
            lines.forEach(line -> {
                try { generatedPairs.add(mapper.readValue(line, TrainingPair.class)); }
                catch (Exception ignored) {}
            });
            log.info("Paires SFT restaurées depuis {}: {}", pairsFile.getFileName(), generatedPairs.size());
        } catch (Exception e) {
            log.warn("Impossible de charger {}: {}", pairsFile, e.getMessage());
        }
    }

    public record GenerationTask(String taskId, Status status, int pairsGenerated, int chunksProcessed,
                                  int totalChunks, String error, Instant createdAt) {
        public enum Status { PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED }
    }

    /**
     * Lance la génération asynchrone. {@code maxChunks=0} signifie "tous les chunks".
     * Retourne {@code null} si une génération est déjà en cours (appelant doit retourner 409).
     */
    public String submit(int maxChunks) {
        if (!generationRunning.compareAndSet(false, true)) {
            return null;
        }
        String taskId = UUID.randomUUID().toString();
        tasks.put(taskId, new GenerationTask(taskId, GenerationTask.Status.PENDING, 0, 0, 0, null, Instant.now()));
        DatasetGeneratorService proxy = (self != null) ? self : this;
        proxy.generateAsync(taskId, maxChunks);
        return taskId;
    }

    public GenerationTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    public List<GenerationTask> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    public boolean cancelTask(String taskId) {
        GenerationTask task = tasks.get(taskId);
        if (task == null) return false;
        if (task.status() == GenerationTask.Status.COMPLETED
                || task.status() == GenerationTask.Status.FAILED
                || task.status() == GenerationTask.Status.CANCELLED) return false;
        cancelledTasks.add(taskId);
        tasks.put(taskId, new GenerationTask(taskId, GenerationTask.Status.CANCELLED,
                task.pairsGenerated(), task.chunksProcessed(), task.totalChunks(),
                "Annulé par l'utilisateur", task.createdAt()));
        generationRunning.set(false);
        return true;
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void cleanupOldTasks() {
        Instant cutoff = Instant.now().minusSeconds(3600);
        tasks.entrySet().removeIf(e -> {
            GenerationTask t = e.getValue();
            return (t.status() == GenerationTask.Status.COMPLETED
                    || t.status() == GenerationTask.Status.FAILED
                    || t.status() == GenerationTask.Status.CANCELLED)
                    && t.createdAt() != null && t.createdAt().isBefore(cutoff);
        });
    }

    public List<TrainingPair> getAllPairs() {
        return List.copyOf(generatedPairs);
    }

    public int generate(String taskId, int minPairs) {
        tasks.put(taskId, new GenerationTask(taskId, GenerationTask.Status.PENDING, 0, 0, 0, null, Instant.now()));
        DatasetGeneratorService proxy = (self != null) ? self : this;
        proxy.generateAsync(taskId, 0);
        return generatedPairs.size();
    }

    @Async
    @SuppressWarnings("unchecked")
    protected void generateAsync(String taskId, int maxChunks) {
        try {
            generatedPairs.clear();
            String collectionId = chromaDbClient.getOrCreateCollection(COLLECTION_NAME);

            Map<String, Object> allDocs = chromaDbClient.getAllDocuments(collectionId);
            List<String> documents = allDocs != null ? (List<String>) allDocs.get("documents") : null;
            List<Map<String, String>> metadatas = allDocs != null ? (List<Map<String, String>>) allDocs.get("metadatas") : null;
            List<String> ids = allDocs != null ? (List<String>) allDocs.get("ids") : null;

            if (documents != null && maxChunks > 0 && documents.size() > maxChunks) {
                documents = documents.subList(0, maxChunks);
                metadatas = metadatas != null ? metadatas.subList(0, maxChunks) : null;
                ids       = ids       != null ? ids.subList(0, maxChunks)       : null;
                log.info("Limitation à {} chunks (sur {} disponibles)", maxChunks, allDocs != null && allDocs.get("documents") instanceof List<?> l ? l.size() : "?");
            }

            if (documents == null || documents.isEmpty()) {
                log.info("Aucun chunk disponible dans ChromaDB pour la génération (taskId={})", taskId);
                tasks.put(taskId, new GenerationTask(taskId, GenerationTask.Status.COMPLETED, 0, 0, 0, null, Instant.now()));
                return;
            }

            if (metadatas == null || ids == null
                    || metadatas.size() != documents.size()
                    || ids.size() != documents.size()) {
                String msg = String.format("Réponse ChromaDB incohérente: documents=%d, metadatas=%s, ids=%s",
                        documents.size(),
                        metadatas != null ? metadatas.size() : "null",
                        ids != null ? ids.size() : "null");
                log.error("{} (taskId={})", msg, taskId);
                tasks.put(taskId, new GenerationTask(taskId, GenerationTask.Status.FAILED, 0, 0, 0, msg, Instant.now()));
                return;
            }

            int total = documents.size();
            tasks.put(taskId, new GenerationTask(taskId, GenerationTask.Status.PROCESSING, 0, 0, total, null, Instant.now()));
            log.info("Génération de dataset: {} chunks à traiter", total);

            int pairsCount = 0;

            for (int i = 0; i < documents.size(); i++) {
                // Point d'annulation
                if (cancelledTasks.contains(taskId)) {
                    log.info("Génération {} annulée à l'itération {}", taskId, i);
                    tasks.put(taskId, new GenerationTask(taskId, GenerationTask.Status.CANCELLED,
                            pairsCount, i, total, "Annulé par l'utilisateur", Instant.now()));
                    return;
                }

                String chunkText = documents.get(i);
                Map<String, String> meta = metadatas.get(i);
                String sourceFile = meta.getOrDefault("sourceFile", "inconnu");

                try {
                    List<TrainingPair> pairs = generatePairsFromChunk(chunkText, sourceFile);
                    generatedPairs.addAll(pairs);
                    pairsCount += pairs.size();
                } catch (Exception e) {
                    log.warn("Erreur sur chunk {}: {}", ids.get(i), e.getMessage());
                }

                final int fp = pairsCount;
                tasks.put(taskId, new GenerationTask(
                        taskId, GenerationTask.Status.PROCESSING, fp, i + 1, total, null, Instant.now()));
            }

            int uniqueCount = deduplicate();
            tasks.put(taskId, new GenerationTask(
                    taskId, GenerationTask.Status.COMPLETED, uniqueCount, total, total, null, Instant.now()));
            log.info("Génération terminée: {} paires uniques (sur {} générées) depuis {} chunks",
                    uniqueCount, pairsCount, total);
            persistPairs();

        } catch (Exception e) {
            if (cancelledTasks.contains(taskId)) {
                log.info("Génération dataset {} interrompue par annulation: {}", taskId, e.getMessage());
                tasks.put(taskId, new GenerationTask(
                        taskId, GenerationTask.Status.CANCELLED, 0, 0, 0, "Annulé par l'utilisateur", Instant.now()));
            } else {
                log.error("Erreur génération dataset {}: {}", taskId, e.getMessage(), e);
                tasks.put(taskId, new GenerationTask(
                        taskId, GenerationTask.Status.FAILED, 0, 0, 0, e.getMessage(), Instant.now()));
            }
        } finally {
            generationRunning.set(false);
            cancelledTasks.remove(taskId);
        }
    }

    /**
     * Génère 3 types de paires pour un chunk : question/réponse, résumé, classification.
     */
    private List<TrainingPair> generatePairsFromChunk(String chunkText, String sourceFile) {
        List<TrainingPair> pairs = new ArrayList<>();

        // 1. Paire Question / Réponse
        String qaPrompt = """
                À partir du texte suivant, génère exactement une question pertinente et sa réponse.
                Le texte provient d'un document d'exploitation autoroutière.
                Réponds UNIQUEMENT en JSON valide avec les clés "question" et "answer".
                Ne mets aucun texte avant ou après le JSON.

                Texte:
                %s""".formatted(chunkText);

        String qaJson = llmChatClient.chat("Tu génères des paires d'entraînement pour un LLM.", qaPrompt);
        TrainingPair qaPair = parseQaPair(qaJson, chunkText, sourceFile);
        if (qaPair != null) pairs.add(qaPair);

        // 2. Paire Résumé
        String summaryPrompt = """
                Résume le texte suivant en 2-3 phrases concises.
                Le texte provient d'un document d'exploitation autoroutière.
                Réponds UNIQUEMENT en JSON valide avec les clés "instruction" et "summary".
                L'instruction doit demander un résumé du contenu.

                Texte:
                %s""".formatted(chunkText);

        String summaryJson = llmChatClient.chat("Tu génères des paires d'entraînement pour un LLM.", summaryPrompt);
        TrainingPair summaryPair = parseSummaryPair(summaryJson, chunkText, sourceFile);
        if (summaryPair != null) pairs.add(summaryPair);

        // 3. Paire Classification
        String classifPrompt = """
                Classe le texte suivant dans exactement UNE de ces catégories :
                - procedures (procédures d'exploitation, interventions, sécurité, maintenance)
                - evenements (messages d'événements, incidents, trafic, météo)
                - nomenclatures (codes, équipements, tronçons, référentiels)
                - reglementation (réglementation, conformité, règles internes)

                Réponds UNIQUEMENT en JSON valide avec les clés "category" et "reason".

                Texte:
                %s""".formatted(chunkText);

        String classifJson = llmChatClient.chat("Tu es un classificateur de documents autoroutiers.", classifPrompt);
        TrainingPair classifPair = parseClassificationPair(classifJson, chunkText, sourceFile);
        if (classifPair != null) pairs.add(classifPair);

        return pairs;
    }

    private TrainingPair parseQaPair(String json, String chunkText, String source) {
        try {
            JsonNode node = mapper.readTree(extractJson(json));
            String question = node.get("question").asText();
            String answer = node.get("answer").asText();
            boolean wellFormed = question.length() >= 10 && answer.length() >= 20;
            double confidence = wellFormed ? groundedConfidence(answer, chunkText) : 0.4;
            return TrainingPair.of(question, answer, source, "qa", "question_answer", confidence);
        } catch (Exception e) {
            log.debug("Parsing QA échoué: {}", e.getMessage());
            return null;
        }
    }

    private TrainingPair parseSummaryPair(String json, String chunkText, String source) {
        try {
            JsonNode node = mapper.readTree(extractJson(json));
            String instruction = node.get("instruction").asText();
            String summary = node.get("summary").asText();
            boolean wellFormed = instruction.length() >= 10 && summary.length() >= 30;
            double confidence = wellFormed ? groundedConfidence(summary, chunkText) : 0.4;
            return TrainingPair.of(instruction, summary, source, "summary", "summarization", confidence);
        } catch (Exception e) {
            log.debug("Parsing résumé échoué: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Score de confiance <b>ancré dans la source</b> plutôt qu'un simple test de longueur.
     *
     * <p>Mesure la part des mots de contenu de la réponse présents dans le chunk d'origine
     * (proxy d'ancrage / anti-hallucination), puis la combine à une base : une réponse bien
     * formée et fortement ancrée tend vers 1.0 ; bien formée mais peu ancrée (donc potentiellement
     * inventée) reste vers 0.6, et passe sous les seuils stricts (0.85 / 0.9) des recettes.</p>
     */
    private double groundedConfidence(String answer, String chunkText) {
        double grounding = grounding(answer, chunkText);   // 0..1
        double score = 0.6 + 0.4 * grounding;
        return Math.max(0.0, Math.min(1.0, score));
    }

    /** Fraction des mots de contenu (>3 lettres) de {@code text} qui apparaissent dans {@code source}. */
    private double grounding(String text, String source) {
        Set<String> textWords = contentWords(text);
        if (textWords.isEmpty()) return 0.0;
        Set<String> sourceWords = contentWords(source);
        if (sourceWords.isEmpty()) return 0.0;
        long hits = textWords.stream().filter(sourceWords::contains).count();
        return (double) hits / textWords.size();
    }

    private Set<String> contentWords(String text) {
        Set<String> words = new java.util.HashSet<>();
        for (String w : text.toLowerCase().split("[^\\p{L}\\p{Nd}]+")) {
            if (w.length() > 3) words.add(w);
        }
        return words;
    }

    private TrainingPair parseClassificationPair(String json, String chunkText, String source) {
        try {
            JsonNode node = mapper.readTree(extractJson(json));
            String category = node.get("category").asText().toLowerCase().trim();
            double confidence = VALID_CATEGORIES.contains(category) ? 0.8 : 0.4;
            String instruction = "Classe le texte suivant dans la bonne catégorie : " + chunkText;
            String response = "Catégorie : " + category;
            return TrainingPair.of(instruction, response, source, category, "classification", confidence);
        } catch (Exception e) {
            log.debug("Parsing classification échoué: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Supprime les paires en double (même instruction + même réponse) accumulées dans
     * {@link #generatedPairs}, en conservant la première occurrence. Renvoie le nombre de
     * paires uniques restantes. Évite de sur-pondérer un contenu répété lors du fine-tuning.
     */
    private int deduplicate() {
        Set<String> seen = new java.util.HashSet<>();
        List<TrainingPair> unique = new ArrayList<>();
        for (TrainingPair pair : generatedPairs) {
            String user = pair.conversations().stream()
                    .filter(m -> "user".equals(m.role())).map(TrainingPair.Message::content)
                    .findFirst().orElse("");
            String assistant = pair.conversations().stream()
                    .filter(m -> "assistant".equals(m.role())).map(TrainingPair.Message::content)
                    .findFirst().orElse("");
            String key = (user + " " + assistant).toLowerCase().strip();
            if (seen.add(key)) {
                unique.add(pair);
            }
        }
        if (unique.size() != generatedPairs.size()) {
            generatedPairs.clear();
            generatedPairs.addAll(unique);
        }
        return unique.size();
    }

    private void persistPairs() {
        try {
            Files.createDirectories(pairsFile.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(pairsFile,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (TrainingPair pair : generatedPairs) {
                    writer.write(mapper.writeValueAsString(pair));
                    writer.newLine();
                }
            }
            log.info("Paires SFT persistées: {} → {}", generatedPairs.size(), pairsFile);
        } catch (Exception e) {
            log.warn("Impossible de persister les paires SFT: {}", e.getMessage());
        }
    }

    /**
     * Extrait le premier bloc JSON valide d'une réponse LLM.
     * Gère les blocs markdown ```json ... ``` et le texte autour.
     */
    private String extractJson(String text) {
        if (text == null || text.isBlank()) return "{}";

        // Strip markdown code fences (```json ... ``` or ``` ... ```)
        String cleaned = text.strip();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).strip();
            }
        }

        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return "{}";
    }
}
