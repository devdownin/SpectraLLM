package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Hybrid search: combines ChromaDB vector search with BM25 full-text search
 * via Reciprocal Rank Fusion (RRF).
 *
 * <p>Two fusion strategies are supported (config {@code spectra.hybrid-search.fusion-mode}):
 * <ul>
 *   <li>{@code rrf} (default): score(d) = w_v / (k + rank_v) + w_bm25 / (k + rank_bm25).
 *       Rank-based, score-magnitude agnostic, robust — recommended default.</li>
 *   <li>{@code weighted}: score(d) = w_v · normSim_v(d) + w_bm25 · normBm25(d), where each
 *       component is min-max normalized to [0,1] across its own candidate list. Keeps the
 *       magnitude of strong matches but is more sensitive to score outliers.</li>
 * </ul>
 * <p>Active only when {@code spectra.hybrid-search.enabled=true}.
 */
@Service
@ConditionalOnProperty(prefix = "spectra.hybrid-search", name = "enabled", havingValue = "true")
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);
    private static final int RRF_K = 60;

    private final ChromaDbClient chromaDbClient;
    private final FtsService ftsService;
    private final SpectraProperties props;

    public HybridSearchService(ChromaDbClient chromaDbClient,
                                FtsService ftsService,
                                SpectraProperties props) {
        this.chromaDbClient = chromaDbClient;
        this.ftsService = ftsService;
        this.props = props;
    }

    /**
     * A document candidate after RRF fusion.
     */
    public record HybridChunk(
            String id,
            String text,
            String sourceFile,
            double vectorDistance,  // cosine distance from ChromaDB (1.0 if BM25-only)
            float  bm25Score,       // raw BM25 score (0f if vector-only)
            double rrfScore         // combined RRF score (higher = more relevant)
    ) {}

    /**
     * Run hybrid search and return up to {@code topCandidates} results sorted by RRF score desc.
     *
     * @param question       user query text
     * @param queryEmbedding query vector (for ChromaDB)
     * @param collectionId   ChromaDB collection ID
     * @param collectionName ChromaDB collection name (for FTS index lookup)
     * @param topCandidates  max results to return
     */
    @SuppressWarnings("unchecked")
    public List<HybridChunk> search(String question,
                                    List<Float> queryEmbedding,
                                    String collectionId,
                                    String collectionName,
                                    int topCandidates) {
        SpectraProperties.HybridSearchProperties hybridProps =
                props.hybridSearch() != null ? props.hybridSearch()
                        : new SpectraProperties.HybridSearchProperties(true, 20, 1.0f, "rrf");

        int topBm25 = hybridProps.effectiveTopBm25();
        float bm25Weight = hybridProps.effectiveBm25Weight();
        float vecWeight = 1.0f;  // vector weight always 1
        String fusionMode = hybridProps.effectiveFusionMode();

        // --- Run both searches in parallel ---
        CompletableFuture<Map<String, Object>> vectorFuture = CompletableFuture.supplyAsync(
                () -> chromaDbClient.query(collectionId, queryEmbedding, topCandidates));

        CompletableFuture<List<BM25Index.ScoredDoc>> bm25Future = CompletableFuture.supplyAsync(
                () -> ftsService.search(question, collectionName, topBm25));

        Map<String, Object> vectorResult;
        List<BM25Index.ScoredDoc> bm25Results;
        try {
            vectorResult = vectorFuture.get();
            bm25Results  = bm25Future.get();
        } catch (Exception e) {
            log.warn("Hybrid search parallel execution failed: {}", e.getMessage());
            // Fall back to vector-only
            vectorResult = chromaDbClient.query(collectionId, queryEmbedding, topCandidates);
            bm25Results  = List.of();
        }

        // --- Parse vector results ---
        List<List<String>>               docsList     = (List<List<String>>)               vectorResult.get("documents");
        List<List<Map<String, String>>>  metasList    = (List<List<Map<String, String>>>)  vectorResult.get("metadatas");
        List<List<Double>>               distsList    = (List<List<Double>>)               vectorResult.get("distances");
        List<List<String>>               idsList      = (List<List<String>>)               vectorResult.get("ids");

        List<String>              vecDocs  = (docsList  != null && !docsList.isEmpty())  ? docsList.getFirst()  : List.of();
        List<Map<String, String>> vecMetas = (metasList != null && !metasList.isEmpty()) ? metasList.getFirst() : List.of();
        List<Double>              vecDists = (distsList != null && !distsList.isEmpty())  ? distsList.getFirst() : List.of();
        List<String>              vecIds   = (idsList   != null && !idsList.isEmpty())   ? idsList.getFirst()   : List.of();

        // Build map: chunkId → vector rank (1-indexed)
        Map<String, Integer>  vecRankByid = new LinkedHashMap<>();
        Map<String, Double>   vecDistById = new LinkedHashMap<>();
        Map<String, String>   vecTextById = new LinkedHashMap<>();
        Map<String, String>   vecSrcById  = new LinkedHashMap<>();

        for (int i = 0; i < vecDocs.size(); i++) {
            String id = i < vecIds.size() ? vecIds.get(i) : ("__vec_" + i);
            vecRankByid.put(id, i + 1);
            vecDistById.put(id, vecDists.isEmpty() ? 0.0 : vecDists.get(i));
            vecTextById.put(id, vecDocs.get(i));
            String src = vecMetas.isEmpty() ? "inconnu"
                    : vecMetas.get(i).getOrDefault("sourceFile", "inconnu");
            vecSrcById.put(id, src);
        }

        // Build map: chunkId → BM25 rank (1-indexed)
        Map<String, Integer> bm25RankById  = new LinkedHashMap<>();
        Map<String, Float>   bm25ScoreById = new LinkedHashMap<>();
        for (int i = 0; i < bm25Results.size(); i++) {
            BM25Index.ScoredDoc doc = bm25Results.get(i);
            bm25RankById.put(doc.id(), i + 1);
            bm25ScoreById.put(doc.id(), doc.score());
        }

        // --- Fusion (RRF par défaut, ou pondérée normalisée) ---
        Set<String> allIds = new LinkedHashSet<>();
        allIds.addAll(vecRankByid.keySet());
        allIds.addAll(bm25RankById.keySet());

        Map<String, Double> rrfScores = "weighted".equals(fusionMode)
                ? weightedFusion(allIds, vecDistById, bm25ScoreById, vecWeight, bm25Weight)
                : rrfFusion(allIds, vecRankByid, bm25RankById, vecWeight, bm25Weight);

        // --- Build output sorted by RRF score ---
        // Prepare BM25-only doc details (text/source from FTS index)
        Map<String, BM25Index.ScoredDoc> bm25DocById = bm25Results.stream()
                .collect(Collectors.toMap(BM25Index.ScoredDoc::id, d -> d));

        List<HybridChunk> results = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topCandidates)
                .map(e -> {
                    String id = e.getKey();
                    double rrf = e.getValue();
                    String text    = vecTextById.containsKey(id) ? vecTextById.get(id)
                            : (bm25DocById.containsKey(id) ? bm25DocById.get(id).text() : "");
                    String src     = vecSrcById.containsKey(id)  ? vecSrcById.get(id)
                            : (bm25DocById.containsKey(id) ? bm25DocById.get(id).sourceFile() : "inconnu");
                    double dist    = vecDistById.getOrDefault(id, 1.0);
                    float  bm25Sc  = bm25ScoreById.getOrDefault(id, 0f);
                    return new HybridChunk(id, text, src, dist, bm25Sc, rrf);
                })
                .toList();

        int vecOnly  = (int) results.stream().filter(c -> c.bm25Score() == 0f).count();
        int bm25Only = (int) results.stream().filter(c -> c.vectorDistance() >= 1.0).count();
        int both     = results.size() - vecOnly - bm25Only;
        log.info("Hybrid search [{}]: {} results (vec-only={}, bm25-only={}, both={})",
                fusionMode, results.size(), vecOnly, bm25Only, both);

        return results;
    }

    /**
     * Reciprocal Rank Fusion : combine les rangs (1-indexés) des deux listes.
     * Insensible à l'échelle des scores → robuste, recommandé par défaut.
     */
    private static Map<String, Double> rrfFusion(Set<String> allIds,
                                                 Map<String, Integer> vecRankById,
                                                 Map<String, Integer> bm25RankById,
                                                 float vecWeight, float bm25Weight) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (String id : allIds) {
            double s = 0.0;
            Integer vr = vecRankById.get(id);
            if (vr != null) s += vecWeight / (RRF_K + vr);
            Integer br = bm25RankById.get(id);
            if (br != null) s += bm25Weight / (RRF_K + br);
            scores.put(id, s);
        }
        return scores;
    }

    /**
     * Fusion pondérée : combinaison convexe de la similarité vectorielle (1 − distance cosinus)
     * et du score BM25, chacun normalisé min-max sur sa propre liste de candidats.
     * Conserve la magnitude des correspondances fortes (contrairement à RRF qui ne voit que le rang).
     */
    private static Map<String, Double> weightedFusion(Set<String> allIds,
                                                       Map<String, Double> vecDistById,
                                                       Map<String, Float> bm25ScoreById,
                                                       float vecWeight, float bm25Weight) {
        // Similarité vectorielle = 1 − distance cosinus (plus haut = plus pertinent)
        Map<String, Double> vecSim = new LinkedHashMap<>();
        for (var e : vecDistById.entrySet()) vecSim.put(e.getKey(), 1.0 - e.getValue());
        Map<String, Double> normVec = minMaxNormalize(vecSim);

        Map<String, Double> bm25 = new LinkedHashMap<>();
        for (var e : bm25ScoreById.entrySet()) bm25.put(e.getKey(), e.getValue().doubleValue());
        Map<String, Double> normBm25 = minMaxNormalize(bm25);

        Map<String, Double> scores = new LinkedHashMap<>();
        for (String id : allIds) {
            double s = vecWeight * normVec.getOrDefault(id, 0.0)
                     + bm25Weight * normBm25.getOrDefault(id, 0.0);
            scores.put(id, s);
        }
        return scores;
    }

    /**
     * Normalisation min-max vers [0,1]. Une liste à un seul élément (ou min == max)
     * renvoie 1.0 pour chaque entrée (le candidat est, par défaut, le meilleur de sa liste).
     */
    private static Map<String, Double> minMaxNormalize(Map<String, Double> values) {
        if (values.isEmpty()) return Map.of();
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double v : values.values()) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        Map<String, Double> norm = new LinkedHashMap<>();
        double range = max - min;
        for (var e : values.entrySet()) {
            norm.put(e.getKey(), range > 0 ? (e.getValue() - min) / range : 1.0);
        }
        return norm;
    }
}
