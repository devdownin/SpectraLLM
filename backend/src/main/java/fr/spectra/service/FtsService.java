package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.model.TextChunk;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full-Text Search service backed by in-memory BM25 indices (one per ChromaDB collection).
 *
 * <p>Index lifecycle:
 * <ul>
 *   <li>Rebuilt asynchronously from disk (or fallback to ChromaDB) at startup for the default collection.</li>
 *   <li>Kept in sync via {@link #indexChunks} (called by IngestionTaskExecutor) and
 *       {@link #removeBySource} (called by DocumentController).</li>
 * </ul>
 */
@Service
public class FtsService {

    private static final Logger log = LoggerFactory.getLogger(FtsService.class);
    private static final Path INDEX_DIR = Path.of("./data/fts-index");

    /**
     * Allowlist for deserializing a persisted BM25 index: only the index class itself
     * and JDK value types (the maps/sets/strings/numbers it is built from) may be
     * reconstructed; every other class is rejected. This closes the native-deserialization
     * gadget-chain surface — should a malicious {@code *.bin} ever land in {@link #INDEX_DIR},
     * it cannot instantiate arbitrary classes to trigger code execution.
     */
    private static final ObjectInputFilter FTS_INDEX_FILTER =
            ObjectInputFilter.Config.createFilter("fr.spectra.service.BM25Index;java.util.*;java.lang.*;!*");

    private final ChromaDbClient chromaDbClient;
    private final SpectraProperties props;

    /** One BM25 index per collection name. */
    private final Map<String, BM25Index> indices = new ConcurrentHashMap<>();

    /**
     * Collections dont l'index en mémoire a divergé du fichier {@code .bin}.
     * La persistance est différée ({@link #flushDirtyIndices}, toutes les 5 s) : sérialiser
     * l'index COMPLET à chaque lot de 10 chunks — qui plus est sous le verrou de la map —
     * rendait l'ingestion quadratique en I/O sur les grosses collections. La sérialisation
     * hors verrou est sûre : {@link BM25Index} se sérialise sous son verrou de lecture.
     * Fenêtre de perte max en cas d'arrêt brutal : 5 s (l'index se reconstruit de toute
     * façon depuis ChromaDB s'il est absent/périmé).
     */
    private final Set<String> dirtyIndices = ConcurrentHashMap.newKeySet();

    /**
     * Empêche deux rebuilds simultanés de la MÊME collection (PostConstruct + retry planifié)
     * de se concurrencer — par collection, pour ne pas bloquer le rebuild d'une autre
     * collection (ré-indexation) pendant celui de la collection par défaut.
     */
    private final Set<String> rebuilding = ConcurrentHashMap.newKeySet();

    /**
     * Collections dont un rebuild a ABOUTI (même vide). Le retry planifié ne concerne que les
     * rebuilds en échec (ChromaDB indisponible) : sans ce marqueur, une collection
     * légitimement vide déclenchait un rebuild + un log par minute, indéfiniment.
     */
    private final Set<String> rebuiltOnce = ConcurrentHashMap.newKeySet();

    /**
     * Index cibles des rebuilds en cours. Les mutations live ({@link #indexChunks},
     * {@link #removeBySource}) y sont répliquées en plus de l'index publié : sans cela,
     * les chunks ingérés PENDANT un rebuild (qui pagine ChromaDB, potentiellement long)
     * étaient écrasés par le {@code indices.put} final et disparaissaient silencieusement
     * du BM25 jusqu'au rebuild suivant. {@link BM25Index#add} étant idempotent par id, un
     * chunk vu à la fois par le pager et par la réplication n'est pas dupliqué.
     */
    private final Map<String, BM25Index> pendingRebuilds = new ConcurrentHashMap<>();

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
        if (indexedCount(coll) == 0 && !rebuiltOnce.contains(coll)) {
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
        if (!rebuilding.add(collectionName)) {
            log.debug("FTS: rebuild déjà en cours — '{}' ignoré", collectionName);
            return;
        }
        log.info("FTS: rebuilding index for collection '{}'", collectionName);
        try {
            // Tentative de chargement depuis le disque d'abord — validée contre ChromaDB :
            // un index périmé (flush différé perdu, suppression non persistée, reset de
            // ChromaDB) servirait sinon des chunks fantômes ou en manque indéfiniment.
            BM25Index diskIndex = loadIndexFromDisk(collectionName);
            if (diskIndex != null) {
                mergeRebuilt(collectionName, diskIndex);
                rebuiltOnce.add(collectionName);
                log.info("FTS: index '{}' loaded from disk ({} chunks)", collectionName, diskIndex.size());
                return;
            }

            // Fallback : reconstruction depuis ChromaDB. L'index cible est enregistré comme
            // « pending » AVANT la première page : tout chunk ingéré pendant le rebuild y est
            // répliqué (cf. pendingRebuilds) et survit donc à la publication finale.
            String collectionId = chromaDbClient.getOrCreateCollection(collectionName);
            BM25Index index = new BM25Index();
            pendingRebuilds.put(collectionName, index);
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

            mergeRebuilt(collectionName, index);
            rebuiltOnce.add(collectionName);
            log.info("FTS: index '{}' rebuilt — {} chunks indexed", collectionName, total);
        } catch (Exception e) {
            log.warn("FTS: could not rebuild index for '{}': {}", collectionName, e.getMessage());
        } finally {
            pendingRebuilds.remove(collectionName);
            rebuilding.remove(collectionName);
        }
    }

    /**
     * Installe l'index reconstruit en FUSIONNANT les chunks indexés en direct pendant le
     * rebuild (ingestion concurrente au démarrage) : l'ancien {@code indices.put} les
     * écrasait — ils disparaissaient de BM25 jusqu'au rebuild suivant. La fusion est
     * atomique par collection ({@code ConcurrentHashMap.merge}), et l'index résultant est
     * marqué dirty pour être persisté par le flush différé.
     */
    private void mergeRebuilt(String collectionName, BM25Index rebuilt) {
        indices.merge(collectionName, rebuilt, (live, fresh) -> {
            fresh.addAll(live); // les ajouts en direct priment (ré-indexation par id, idempotent)
            return fresh;
        });
        dirtyIndices.add(collectionName);
    }

    /** Index a batch of newly ingested chunks. Called from IngestionTaskExecutor. */
    public void indexChunks(List<TextChunk> chunks, String collectionName) {
        indices.compute(collectionName, (k, existing) -> {
            BM25Index index = existing != null ? existing : new BM25Index();
            for (TextChunk chunk : chunks) {
                index.add(chunk.id(), chunk.text(), chunk.sourceFile());
            }
            return index;
        });
        // Réplique dans l'index cible d'un éventuel rebuild en cours (cf. pendingRebuilds).
        BM25Index pending = pendingRebuilds.get(collectionName);
        if (pending != null) {
            for (TextChunk chunk : chunks) {
                pending.add(chunk.id(), chunk.text(), chunk.sourceFile());
            }
        }
        dirtyIndices.add(collectionName);
        log.debug("FTS: indexed {} chunks into '{}'", chunks.size(), collectionName);
    }

    /** Remove all chunks belonging to a source file. Called from DocumentController. */
    public void removeBySource(String sourceFile, String collectionName) {
        indices.compute(collectionName, (k, existing) -> {
            if (existing != null) {
                existing.removeBySource(sourceFile);
            }
            return existing;
        });
        // Réplique la suppression dans l'index cible d'un éventuel rebuild en cours. Fenêtre
        // résiduelle : si le pager ChromaDB relit ces chunks APRÈS cette suppression (avant
        // leur effacement effectif côté ChromaDB), ils réapparaîtront — cas rare, rattrapé
        // par la réconciliation périodique.
        BM25Index pending = pendingRebuilds.get(collectionName);
        if (pending != null) {
            pending.removeBySource(sourceFile);
        }
        if (indices.containsKey(collectionName)) {
            dirtyIndices.add(collectionName);
        }
        log.debug("FTS: removed '{}' from index '{}'", sourceFile, collectionName);
    }

    /**
     * Persiste les index modifiés depuis le dernier passage (voir {@link #dirtyIndices}).
     * Intervalle de 30 s : la sérialisation porte sur l'index COMPLET — à 5 s, une grosse
     * ingestion payait une réécriture intégrale quasi continue. Fenêtre de perte bornée à
     * 30 s (l'index se reconstruit de toute façon depuis ChromaDB s'il est absent/périmé),
     * et le {@code @PreDestroy} flushe à l'arrêt propre.
     */
    @Scheduled(fixedDelay = 30_000)
    public void flushDirtyIndices() {
        for (String collectionName : dirtyIndices) {
            if (dirtyIndices.remove(collectionName)) {
                BM25Index index = indices.get(collectionName);
                if (index != null) {
                    saveIndexToDisk(collectionName, index);
                }
            }
        }
    }

    @jakarta.annotation.PreDestroy
    void flushOnShutdown() {
        flushDirtyIndices();
    }

    private BM25Index loadIndexFromDisk(String collectionName) {
        Path file = INDEX_DIR.resolve(collectionName + ".bin");
        if (!Files.exists(file)) {
            return null;
        }
        try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(file))) {
            ois.setObjectInputFilter(FTS_INDEX_FILTER);
            return (BM25Index) ois.readObject();
        } catch (Exception e) {
            log.warn("FTS: failed to deserialize index for '{}' from disk, rebuilding: {}", collectionName, e.getMessage());
            return null;
        }
    }

    private void saveIndexToDisk(String collectionName, BM25Index index) {
        try {
            Files.createDirectories(INDEX_DIR);
            Path file = INDEX_DIR.resolve(collectionName + ".bin");
            try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(file))) {
                oos.writeObject(index);
            }
        } catch (Exception e) {
            log.warn("FTS: failed to serialize index for '{}' to disk: {}", collectionName, e.getMessage());
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
