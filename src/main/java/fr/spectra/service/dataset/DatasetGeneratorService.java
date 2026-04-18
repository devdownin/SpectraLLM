package fr.spectra.service.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.model.TrainingPair;
import fr.spectra.service.ChromaDbClient;
import fr.spectra.service.LlmChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.UUID;

@Service
public class DatasetGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(DatasetGeneratorService.class);
    private static final String COLLECTION_NAME = "spectra_documents";
    private static final ObjectMapper mapper = new ObjectMapper();

    private final LlmChatClient llmChatClient;
    private final ChromaDbClient chromaDbClient;

    /** Self-reference for @Async proxy — injected lazily to avoid circular dependency. */
    @Lazy @Autowired(required = false)
    private DatasetGeneratorService self;

    /** Toutes les paires générées, accessibles par le service d'export. */
    private final List<TrainingPair> generatedPairs = new CopyOnWriteArrayList<>();

    /** Suivi des tâches de génération. */
    private final Map<String, GenerationTask> tasks = new ConcurrentHashMap<>();

    public DatasetGeneratorService(LlmChatClient llmChatClient, ChromaDbClient chromaDbClient) {
        this.llmChatClient = llmChatClient;
        this.chromaDbClient = chromaDbClient;
    }

    public record GenerationTask(String taskId, Status status, int pairsGenerated, int chunksProcessed,
                                  int totalChunks, String error) {
        public enum Status { PENDING, PROCESSING, COMPLETED, FAILED }
    }

    /**
     * Lance la génération asynchrone de paires d'entraînement à partir de tous les chunks ChromaDB.
     */
    public String submit() {
        String taskId = UUID.randomUUID().toString();
        tasks.put(taskId, new GenerationTask(taskId, GenerationTask.Status.PENDING, 0, 0, 0, null));
        // Route through the Spring proxy so @Async is actually applied.
        DatasetGeneratorService proxy = (self != null) ? self : this;
        proxy.generateAsync(taskId);
        return taskId;
    }

    public GenerationTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    public List<TrainingPair> getAllPairs() {
        return List.copyOf(generatedPairs);
    }

    /**
     * Génère des paires d'entraînement de façon synchrone et retourne le nombre de paires créées.
     * @param taskId identifiant de la tâche
     * @param minPairs non utilisé (conservé pour compatibilité)
     */
    public int generate(String taskId, int minPairs) {
        tasks.put(taskId, new GenerationTask(taskId, GenerationTask.Status.PENDING, 0, 0, 0, null));
        DatasetGeneratorService proxy = (self != null) ? self : this;
        proxy.generateAsync(taskId);
        return generatedPairs.size();
    }

    @Async
    @SuppressWarnings("unchecked")
    protected void generateAsync(String taskId) {
        try {
            // Récupérer tous les chunks depuis ChromaDB via une requête large
            String collectionId = chromaDbClient.getOrCreateCollection(COLLECTION_NAME);

            // On récupère tous les documents de la collection
            Map<String, Object> allDocs = chromaDbClient.getAllDocuments(collectionId);
            List<String> documents = allDocs != null ? (List<String>) allDocs.get("documents") : null;
            List<Map<String, String>> metadatas = allDocs != null ? (List<Map<String, String>>) allDocs.get("metadatas") : null;
            List<String> ids = allDocs != null ? (List<String>) allDocs.get("ids") : null;

            if (documents == null || documents.isEmpty()) {
                log.info("Aucun chunk disponible dans ChromaDB pour la génération (taskId={})", taskId);
                tasks.put(taskId, new GenerationTask(taskId, GenerationTask.Status.COMPLETED, 0, 0, 0, null));
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
                tasks.put(taskId, new GenerationTask(taskId, GenerationTask.Status.FAILED, 0, 0, 0, msg));
                return;
            }

            int total = documents.size();
            tasks.put(taskId, new GenerationTask(taskId, GenerationTask.Status.PROCESSING, 0, 0, total, null));
            log.info("Génération de dataset: {} chunks à traiter", total);

            int pairsCount = 0;

            for (int i = 0; i < documents.size(); i++) {
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

                tasks.put(taskId, new GenerationTask(
                        taskId, GenerationTask.Status.PROCESSING, pairsCount, i + 1, total, null));
            }

            tasks.put(taskId, new GenerationTask(
                    taskId, GenerationTask.Status.COMPLETED, pairsCount, total, total, null));
            log.info("Génération terminée: {} paires depuis {} chunks", pairsCount, total);

        } catch (Exception e) {
            log.error("Erreur génération dataset {}: {}", taskId, e.getMessage(), e);
            tasks.put(taskId, new GenerationTask(
                    taskId, GenerationTask.Status.FAILED, 0, 0, 0, e.getMessage()));
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
        TrainingPair qaPair = parseQaPair(qaJson, sourceFile);
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
        TrainingPair summaryPair = parseSummaryPair(summaryJson, sourceFile);
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

    private TrainingPair parseQaPair(String json, String source) {
        try {
            JsonNode node = mapper.readTree(extractJson(json));
            String question = node.get("question").asText();
            String answer = node.get("answer").asText();
            return TrainingPair.of(question, answer, source, "qa", "question_answer", 0.9);
        } catch (Exception e) {
            log.debug("Parsing QA échoué: {}", e.getMessage());
            return null;
        }
    }

    private TrainingPair parseSummaryPair(String json, String source) {
        try {
            JsonNode node = mapper.readTree(extractJson(json));
            String instruction = node.get("instruction").asText();
            String summary = node.get("summary").asText();
            return TrainingPair.of(instruction, summary, source, "summary", "summarization", 0.85);
        } catch (Exception e) {
            log.debug("Parsing résumé échoué: {}", e.getMessage());
            return null;
        }
    }

    private TrainingPair parseClassificationPair(String json, String chunkText, String source) {
        try {
            JsonNode node = mapper.readTree(extractJson(json));
            String category = node.get("category").asText();
            String instruction = "Classe le texte suivant dans la bonne catégorie : " + chunkText;
            String response = "Catégorie : " + category;
            return TrainingPair.of(instruction, response, source, category, "classification", 0.8);
        } catch (Exception e) {
            log.debug("Parsing classification échoué: {}", e.getMessage());
            return null;
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
