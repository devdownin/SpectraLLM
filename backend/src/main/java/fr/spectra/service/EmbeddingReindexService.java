package fr.spectra.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ré-indexation d'une collection après un changement de modèle d'embedding.
 *
 * <p><b>Pourquoi.</b> La protection de cohérence ({@link ChromaDbClient}) bloque toute
 * collection dont l'estampille ne correspond plus au modèle d'embedding actif — c'est
 * la bonne réaction, mais la remédiation restait manuelle (ré-ingérer tous les documents).
 * Ce service reconstruit l'index <b>en place</b> : les textes et métadonnées des chunks
 * sont conservés, seuls les vecteurs sont recalculés avec le modèle actif, puis la
 * collection est ré-estampillée. Pas de ré-ingestion des fichiers sources.</p>
 *
 * <p><b>Sûreté.</b> Pendant la ré-indexation, la collection reste bloquée pour le RAG
 * (l'estampille n'est mise à jour qu'à la toute fin, une fois tous les vecteurs
 * recalculés) : aucun état mixte n'est jamais interrogeable. Une seule ré-indexation à
 * la fois — le serveur d'embedding est déjà le goulot d'étranglement.</p>
 */
@Service
public class EmbeddingReindexService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingReindexService.class);
    /** Chunks lus/ré-embeddés par page (borne la mémoire et la taille des batches d'embedding). */
    private static final int PAGE_SIZE = 16;

    private final ChromaDbClient chromaDbClient;
    private final EmbeddingService embeddingService;
    private final ModelRegistryService modelRegistry;

    private final Map<String, ReindexStatus> statuses = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EmbeddingReindexService(ChromaDbClient chromaDbClient,
                                   EmbeddingService embeddingService,
                                   ModelRegistryService modelRegistry) {
        this.chromaDbClient = chromaDbClient;
        this.embeddingService = embeddingService;
        this.modelRegistry = modelRegistry;
    }

    /**
     * Démarre la ré-indexation asynchrone d'une collection avec le modèle d'embedding actif.
     *
     * @throws IllegalStateException si une ré-indexation est déjà en cours (409)
     */
    public ReindexStatus start(String collection) {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("Le nom de la collection est requis");
        }
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Une ré-indexation est déjà en cours — attendez sa fin "
                    + "(une seule à la fois : le serveur d'embedding est le goulot d'étranglement).");
        }

        String targetModel = modelRegistry.getActiveEmbeddingModel();
        ReindexStatus initial = ReindexStatus.started(collection, targetModel);
        statuses.put(collection, initial);
        log.info("Ré-indexation de '{}' avec le modèle d'embedding '{}'", collection, targetModel);

        CompletableFuture.runAsync(() -> run(collection, targetModel));
        return initial;
    }

    /** Statuts de toutes les ré-indexations connues (en cours et terminées). */
    public List<ReindexStatus> statuses() {
        return List.copyOf(statuses.values());
    }

    private void run(String collection, String targetModel) {
        try {
            String collectionId = chromaDbClient.resolveCollectionIdUnchecked(collection);
            int total = chromaDbClient.count(collectionId);
            update(collection, s -> s.withTotal(total));

            int offset = 0;
            int processed = 0;
            while (true) {
                Map<String, Object> page = chromaDbClient.getDocumentsPaged(collectionId, PAGE_SIZE, offset);
                List<String> ids = stringList(page, "ids");
                List<String> documents = stringList(page, "documents");
                if (ids.isEmpty()) {
                    break;
                }

                // Chunks vides (texte nul/blanc) : on conserve leur vecteur existant plutôt
                // que d'envoyer une chaîne vide au serveur d'embedding.
                List<String> embeddableIds = new ArrayList<>();
                List<String> embeddableTexts = new ArrayList<>();
                for (int i = 0; i < ids.size(); i++) {
                    String text = i < documents.size() ? documents.get(i) : null;
                    if (text != null && !text.isBlank()) {
                        embeddableIds.add(ids.get(i));
                        embeddableTexts.add(text);
                    }
                }

                if (!embeddableIds.isEmpty()) {
                    List<List<Float>> embeddings = embeddingService.embedBatch(embeddableTexts);
                    chromaDbClient.updateEmbeddings(collectionId, embeddableIds, embeddings);
                }

                processed += ids.size();
                int done = processed;
                update(collection, s -> s.withProcessed(done));
                offset += PAGE_SIZE;
            }

            // Ré-estampiller EN DERNIER : la collection ne redevient interrogeable qu'une
            // fois tous les vecteurs recalculés (jamais d'état mixte servi au RAG).
            chromaDbClient.updateEmbeddingStamp(collection, collectionId, targetModel);
            update(collection, ReindexStatus::completed);
            log.info("Ré-indexation de '{}' terminée : {} chunks ré-embeddés avec '{}'",
                    collection, processed, targetModel);
        } catch (Exception e) {
            log.error("Ré-indexation de '{}' échouée : {}", collection, e.getMessage(), e);
            update(collection, s -> s.failed(e.getMessage()));
        } finally {
            running.set(false);
        }
    }

    private void update(String collection, java.util.function.UnaryOperator<ReindexStatus> updater) {
        statuses.computeIfPresent(collection, (k, s) -> updater.apply(s));
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Map<String, Object> page, String key) {
        if (page != null && page.get(key) instanceof List<?> values) {
            List<String> out = new ArrayList<>(values.size());
            for (Object value : values) {
                out.add(value != null ? value.toString() : null);
            }
            return out;
        }
        return List.of();
    }

    /** Instantané du déroulement d'une ré-indexation. */
    public record ReindexStatus(
            String collection,
            String status,          // RUNNING | COMPLETED | FAILED
            String targetModel,
            int processed,
            int total,
            String error,
            Instant startedAt,
            Instant completedAt) {

        static ReindexStatus started(String collection, String targetModel) {
            return new ReindexStatus(collection, "RUNNING", targetModel, 0, 0, null, Instant.now(), null);
        }

        ReindexStatus withTotal(int newTotal) {
            return new ReindexStatus(collection, status, targetModel, processed, newTotal, error, startedAt, completedAt);
        }

        ReindexStatus withProcessed(int newProcessed) {
            return new ReindexStatus(collection, status, targetModel, newProcessed, total, error, startedAt, completedAt);
        }

        ReindexStatus completed() {
            return new ReindexStatus(collection, "COMPLETED", targetModel, processed, total, null, startedAt, Instant.now());
        }

        ReindexStatus failed(String message) {
            return new ReindexStatus(collection, "FAILED", targetModel, processed, total, message, startedAt, Instant.now());
        }

        /** Vue API sérialisable (champs nuls omis). */
        public Map<String, Object> toApi() {
            Map<String, Object> api = new LinkedHashMap<>();
            api.put("collection", collection);
            api.put("status", status);
            api.put("targetModel", targetModel);
            api.put("processed", processed);
            api.put("total", total);
            if (error != null) api.put("error", error);
            api.put("startedAt", startedAt);
            if (completedAt != null) api.put("completedAt", completedAt);
            return api;
        }
    }
}
