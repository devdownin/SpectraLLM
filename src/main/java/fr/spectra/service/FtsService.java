package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.model.TextChunk;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full-Text Search service backed by in-memory BM25 indices (one per ChromaDB collection).
 *
 * <p>Index lifecycle:
 * <ul>
 *   <li>Rebuilt asynchronously from ChromaDB at startup for the default collection.</li>
 *   <li>Kept in sync via {@link #indexChunks} (called by IngestionTaskExecutor) and
 *       {@link #removeBySource} (called by DocumentController).</li>
 * </ul>
 */
@Service
public class FtsService {

    private static final Logger log = LoggerFactory.getLogger(FtsService.class);

    private final ChromaDbClient chromaDbClient;
    private final SpectraProperties props;

    /** One BM25 index per collection name. */
    private final Map<String, BM25Index> indices = new ConcurrentHashMap<>();

    public FtsService(ChromaDbClient chromaDbClient, SpectraProperties props) {
        this.chromaDbClient = chromaDbClient;
        this.props = props;
    }

    @PostConstruct
    public void rebuildDefaultIndexAsync() {
        CompletableFuture.runAsync(() -> rebuildCollection(defaultCollection()))
                .exceptionally(e -> {
                    log.warn("FTS index rebuild failed for '{}': {}", defaultCollection(), e.getMessage());
                    return null;
                });
    }

    /**
     * Retry guard : si l'index de la collection par défaut est vide 90 secondes après le démarrage
     * (ChromaDB hors ligne au moment du @PostConstruct), relance le rebuild toutes les minutes
     * jusqu'à ce qu'il réussisse.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void retryRebuildIfEmpty() {
        String coll = defaultCollection();
        if (indexedCount(coll) == 0) {
            log.info("FTS: index '{}' vide — tentative de rebuild (ChromaDB peut-être indisponible au démarrage)", coll);
            CompletableFuture.runAsync(() -> rebuildCollection(coll))
                    .exceptionally(e -> {
                        log.warn("FTS: retry rebuild '{}' échoué : {}", coll, e.getMessage());
                        return null;
                    });
        }
    }

    private String defaultCollection() {
        return props.chromadb() != null ? props.chromadb().effectiveCollection() : "spectra_documents";
    }

    /**
     * Rebuilds the BM25 index for a collection by fetching all chunks from ChromaDB.
     * Runs in the background; does not block startup.
     */
    public void rebuildCollection(String collectionName) {
        log.info("FTS: rebuilding index for collection '{}'", collectionName);
        try {
            String collectionId = chromaDbClient.getOrCreateCollection(collectionName);
            BM25Index index = new BM25Index();
            int offset = 0;
            int limit = 500;
            int total = 0;

            while (true) {
                Map<String, Object> page = chromaDbClient.getDocumentsPaged(collectionId, limit, offset);
                List<?> ids       = (List<?>) page.get("ids");
                List<?> documents = (List<?>) page.get("documents");
                List<?> metadatas = (List<?>) page.get("metadatas");

                if (ids == null || ids.isEmpty()) break;

                for (int i = 0; i < ids.size(); i++) {
                    String id         = (String) ids.get(i);
                    String text       = documents != null ? (String) documents.get(i) : "";
                    String sourceFile = "inconnu";
                    if (metadatas != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, String> meta = (Map<String, String>) metadatas.get(i);
                        if (meta != null) sourceFile = meta.getOrDefault("sourceFile", "inconnu");
                    }
                    index.add(id, text, sourceFile);
                    total++;
                }

                if (ids.size() < limit) break;
                offset += limit;
            }

            indices.put(collectionName, index);
            log.info("FTS: index '{}' rebuilt — {} chunks indexed", collectionName, total);
        } catch (Exception e) {
            log.warn("FTS: could not rebuild index for '{}': {}", collectionName, e.getMessage());
        }
    }

    /** Index a batch of newly ingested chunks. Called from IngestionTaskExecutor. */
    public void indexChunks(List<TextChunk> chunks, String collectionName) {
        BM25Index index = indices.computeIfAbsent(collectionName, k -> new BM25Index());
        for (TextChunk chunk : chunks) {
            index.add(chunk.id(), chunk.text(), chunk.sourceFile());
        }
        log.debug("FTS: indexed {} chunks into '{}'", chunks.size(), collectionName);
    }

    /** Remove all chunks belonging to a source file. Called from DocumentController. */
    public void removeBySource(String sourceFile, String collectionName) {
        BM25Index index = indices.get(collectionName);
        if (index != null) {
            index.removeBySource(sourceFile);
            log.debug("FTS: removed '{}' from index '{}'", sourceFile, collectionName);
        }
    }

    /**
     * BM25 search within a collection.
     *
     * @param query          the query text
     * @param collectionName the collection to search in
     * @param topN           maximum results to return
     * @return scored docs sorted by BM25 score descending; empty list if index absent or query fails
     */
    public List<BM25Index.ScoredDoc> search(String query, String collectionName, int topN) {
        BM25Index index = indices.get(collectionName);
        if (index == null || index.size() == 0) {
            log.debug("FTS: no index for '{}', returning empty BM25 results", collectionName);
            return List.of();
        }
        return index.search(query, topN);
    }

    /** Returns the number of indexed documents for a collection (for diagnostics). */
    public int indexedCount(String collectionName) {
        BM25Index index = indices.get(collectionName);
        return index != null ? index.size() : 0;
    }

    /**
     * Returns the FTS status for the given collection.
     * Used by {@code GET /api/status/fts}.
     */
    public FtsStatus getStatus(String collectionName) {
        BM25Index index = indices.get(collectionName);
        int count = index != null ? index.size() : 0;
        return new FtsStatus(count > 0, count, collectionName);
    }

    /**
     * Returns an aggregate status across all indexed collections.
     * Used when no specific collection is requested.
     */
    public FtsStatus getAggregatedStatus() {
        int total = indices.values().stream().mapToInt(BM25Index::size).sum();
        String collections = String.join(", ", indices.keySet());
        return new FtsStatus(total > 0, total, collections.isBlank() ? "(none)" : collections);
    }

    /** FTS index status snapshot. */
    public record FtsStatus(boolean ready, int indexedChunks, String collections) {}
}
