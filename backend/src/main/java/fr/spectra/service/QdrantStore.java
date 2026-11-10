package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Native Qdrant storage. Logical collection IDs are stable aliases. */
@Component
@ConditionalOnProperty(name = "spectra.vector-store", havingValue = "qdrant", matchIfMissing = true)
final class QdrantStore {
    private static final Logger log = LoggerFactory.getLogger(QdrantStore.class);
    private static final String PREFIX = "spectra_data_";
    private static final int PAGE = 256;
    private static final String LEXICAL = "spectra_lexical";
    private static final String DENSE = "";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final WebClient http;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final String baseUrl;
    private final Map<String, Boolean> sparseCollections = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> rebuilds = new HashMap<>();
    private final AtomicLong mutation = new AtomicLong();
    private final ThreadLocal<Cursor> cursor = new ThreadLocal<>();

    QdrantStore(@Qualifier("qdrantWebClient") WebClient http,
                @Value("${spectra.qdrant.base-url:http://qdrant:6333}") String baseUrl,
                ObjectMapper mapper, MeterRegistry meters) {
        this.mapper = mapper;
        this.meters = meters;
        this.http = http;
        this.baseUrl = baseUrl;
    }

    String baseUrl() {
        return baseUrl;
    }

    void ready() {
        http.get().uri("/readyz").retrieve().toBodilessEntity().block(TIMEOUT);
    }

    synchronized Map<String, Object> resolve(String name, Map<String, Object> stamp) {
        String alias = alias(name);
        if (backing(alias) == null) {
            String physical = create(stamp, 0);
            switchAlias(alias, null, physical);
        }
        return info(name);
    }

    List<Map<String, Object>> collections() {
        return aliases().stream()
                .filter(row -> ((String) row.get("alias_name")).startsWith(PREFIX))
                .map(row -> info(((String) row.get("alias_name")).substring(PREFIX.length()))).toList();
    }

    Map<String, Object> info(String name) {
        var config = object(object(call("GET", path(name), null)).get("config"));
        sparseCollections.put(name, hasSparse(object(config.get("params"))));
        return Map.of("id", name, "name", name, "metadata", object(config.get("metadata")));
    }

    synchronized void beginRebuild(String name) {
        String abandoned = rebuilds.remove(name);
        if (abandoned != null && !abandoned.isEmpty() && !abandoned.equals(backing(alias(name)))) {
            call("DELETE", "/collections/" + abandoned, null);
        }
        rebuilds.put(name, "");
    }

    synchronized void finishRebuild(String name, Map<String, Object> metadata) {
        String staging = rebuilds.get(name);
        if (staging == null || staging.isEmpty()) {
            call("PATCH", path(name), Map.of("metadata", metadata));
            rebuilds.remove(name);
            return;
        }
        if (countAt("/collections/" + staging, Map.of()) != count(name)) {
            throw new IllegalStateException("Qdrant rebuild has a different point count");
        }
        call("PATCH", "/collections/" + staging, Map.of("metadata", metadata));
        switchAlias(alias(name), backing(alias(name)), staging);
        sparseCollections.put(name, nativeHybrid);
        rebuilds.remove(name);
        mutation.incrementAndGet();
    }

    synchronized void update(String name, List<String> ids, List<List<Float>> embeddings) {
        String staging = rebuilds.get(name);
        if (staging == null) throw new IllegalStateException("No Qdrant rebuild is active");
        if (staging.isEmpty()) {
            staging = create(object(info(name).get("metadata")), embeddings.getFirst().size());
            rebuilds.put(name, staging);
        }
        List<Map<String, Object>> original = pointsById(name, ids, List.of("documents", "metadatas"));
        Map<String, Map<String, Object>> payloads = new HashMap<>();
        for (var point : original) {
            var payload = object(point.get("payload"));
            payloads.put((String) payload.get("chunkId"), payload);
        }
        var points = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            var payload = payloads.get(id);
            if (payload == null) throw new IllegalStateException("Qdrant rebuild lost chunk " + id);
            points.add(point(id, embeddings.get(i), payload, nativeHybrid));
        }
        upsert("/collections/" + staging, points);
    }

    void add(String name, List<String> ids, List<String> texts, List<Map<String, String>> metadata,
             List<List<Float>> embeddings) {
        if (ids.isEmpty()) return;
        if (ids.size() != embeddings.size()) throw new IllegalArgumentException("Missing chunk embeddings");
        boolean sparse = ensureDimensions(name, embeddings.getFirst().size());
        var points = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            points.add(point(id, embeddings.get(i),
                    Map.of("chunkId", id, "document", texts.get(i), "metadata", metadata.get(i)), sparse));
        }
        upsert(path(name), points);
        mutation.incrementAndGet();
    }

    private int groupSize;
    private Map<String, Object> searchParams = Map.of();

    private enum HybridStrategy { RRF, DBSF, DENSE, SPARSE }

    private HybridStrategy hybridStrategy = HybridStrategy.RRF;
    private int rrfK = 60;
    private int lexicalCandidates;
    private boolean nativeHybrid;
    private boolean int8;
    private double oversampling = 2.0;
    private int hnswM;
    private int hnswEfConstruct = 200;

    @org.springframework.beans.factory.annotation.Autowired
    void configureStorage(@Value("${spectra.qdrant.native-hybrid:false}") boolean nativeHybrid,
                          @Value("${spectra.qdrant.int8:false}") boolean int8,
                          @Value("${spectra.qdrant.oversampling:2}") double oversampling) {
        if (!Double.isFinite(oversampling) || oversampling < 1 || oversampling > 10) {
            throw new IllegalArgumentException("Qdrant oversampling must be between 1 and 10");
        }
        this.nativeHybrid = nativeHybrid;
        this.int8 = int8;
        this.oversampling = oversampling;
    }

    /** Graphe HNSW des nouvelles collections ; {@code m = 0} laisse la valeur par défaut de Qdrant (16). */
    @org.springframework.beans.factory.annotation.Autowired
    void configureIndex(@Value("${spectra.qdrant.hnsw-m:0}") int m,
                        @Value("${spectra.qdrant.hnsw-ef-construct:200}") int efConstruct) {
        if (m < 0 || m > 64 || efConstruct < 4 || efConstruct > 2000) {
            throw new IllegalArgumentException("Invalid Qdrant HNSW graph settings");
        }
        this.hnswM = m;
        this.hnswEfConstruct = efConstruct;
    }

    /** {@code hnsw_config} d'une nouvelle collection ; {@code m} omis laisse Qdrant choisir. */
    Map<String, Object> hnswConfig() {
        var hnsw = new LinkedHashMap<String, Object>();
        hnsw.put("ef_construct", hnswEfConstruct);
        if (hnswM > 0) hnsw.put("m", hnswM);
        return hnsw;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void configureHybrid(@Value("${spectra.qdrant.hybrid-strategy:rrf}") String strategy,
                         @Value("${spectra.qdrant.rrf-k:60}") int rrfK,
                         @Value("${spectra.qdrant.lexical-candidates:0}") int lexicalCandidates) {
        if (rrfK <= 0 || lexicalCandidates < 0 || lexicalCandidates > 100) {
            throw new IllegalArgumentException("Invalid Qdrant RRF k or lexical candidate count");
        }
        this.hybridStrategy = HybridStrategy.valueOf(strategy.strip().toUpperCase(java.util.Locale.ROOT));
        this.rrfK = rrfK;
        this.lexicalCandidates = lexicalCandidates;
    }

    boolean nativeSingleBranch() {
        return hybridStrategy == HybridStrategy.DENSE || hybridStrategy == HybridStrategy.SPARSE;
    }

    boolean nativeHybridEnabled() {
        return nativeHybrid && groupSize == 0;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void configureSearch(@Value("${spectra.qdrant.group-size:0}") int groupSize,
                         @Value("${spectra.qdrant.hnsw-ef:0}") int ef,
                         @Value("${spectra.qdrant.exact:false}") boolean exact,
                         @Value("${spectra.qdrant.acorn:false}") boolean acorn,
                         @Value("${spectra.qdrant.acorn-max-selectivity:0.4}") double selectivity) {
        if (groupSize < 0 || groupSize > 10 || ef < 0 || !Double.isFinite(selectivity)
                || selectivity < 0 || selectivity > 1) {
            throw new IllegalArgumentException("Invalid Qdrant search settings");
        }
        this.groupSize = groupSize;
        var params = new HashMap<String, Object>();
        if (ef > 0) params.put("hnsw_ef", ef);
        if (exact) params.put("exact", true);
        if (acorn && !exact) params.put("acorn", Map.of("enable", true, "max_selectivity", selectivity));
        searchParams = Map.copyOf(params);
    }

    boolean supportsBatch() {
        // The batch endpoint accepts individual point queries, while grouping has its own endpoint.
        return groupSize == 0;
    }

    private Map<String, Object> queryRequest(List<Float> embedding, int limit, Map<String, Object> where) {
        var body = new LinkedHashMap<String, Object>(Map.of("query", embedding, "limit", limit, "with_payload", true));
        if (where != null) body.put("filter", filter(where));
        var params = new HashMap<>(searchParams);
        if (Boolean.TRUE.equals(params.get("exact"))) params.put("quantization", Map.of("ignore", true));
        else if (int8) params.put("quantization", Map.of("rescore", true, "oversampling", oversampling));
        if (!params.isEmpty()) body.put("params", params);
        return body;
    }

    Map<String, Object> query(String name, List<Float> embedding, int limit, Map<String, Object> where) {
        var body = queryRequest(embedding, limit, where);
        List<Map<String, Object>> points;
        try {
            if (groupSize > 0 && limit > groupSize) {
                var grouped = new LinkedHashMap<>(body);
                grouped.put("group_by", "metadata.sha256");
                grouped.put("group_size", groupSize);
                var result = object(call("POST", path(name) + "/points/query/groups", grouped));
                var hits = rows(result.get("groups")).stream().flatMap(group -> rows(group.get("hits")).stream())
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                if (hits.size() >= limit) {
                    // Grouping excludes points without the grouping key. Keep historical chunks eligible.
                    var legacy = new LinkedHashMap<>(body);
                    var noSha = Map.of("is_empty", Map.of("key", "metadata.sha256"));
                    legacy.put("filter", Map.of("must", where == null ? List.of(noSha) : List.of(filter(where), noSha)));
                    hits.addAll(rows(object(call("POST", path(name) + "/points/query", legacy)).get("points")));
                    hits.sort(java.util.Comparator.comparingDouble(
                            (Map<String, Object> point) -> ((Number) point.get("score")).doubleValue()).reversed());
                    return queryColumns(hits.subList(0, limit));
                }
                // A limited number of sources may still contain all of the evidence needed for the answer.
            }
            points = rows(object(call("POST", path(name) + "/points/query", body)).get("points"));
        } catch (WebClientResponseException.BadRequest e) {
            if (dimension(name) != 0) throw e;
            points = List.of();
        }
        return queryColumns(points);
    }

    List<Map<String, Object>> queryBatch(String name, List<List<Float>> embeddings, int limit,
                                         Map<String, Object> where) {
        var searches = embeddings.stream().map(embedding -> queryRequest(embedding, limit, where)).toList();
        var results = rows(call("POST", path(name) + "/points/query/batch", Map.of("searches", searches)));
        if (results.size() != searches.size()) throw new IllegalStateException("Incomplete Qdrant batch response");
        return results.stream().map(result -> queryColumns(rows(result.get("points")))).toList();
    }

    Map<String, Object> queryHybrid(String name, String question, List<Float> embedding,
                                    int limit, Map<String, Object> where) {
        if (!sparseCollections.computeIfAbsent(name, key -> hasSparse(collectionParams(key)))) return Map.of();
        var sparse = hybridStrategy == HybridStrategy.DENSE ? null : sparseVector(question, false);
        if (sparse != null && ((List<?>) sparse.get("indices")).isEmpty()) {
            return hybridStrategy == HybridStrategy.SPARSE ? Map.of("documents", List.of(List.of())) : Map.of();
        }

        var dense = queryRequest(embedding, limit, where);
        dense.remove("with_payload");
        var lexical = new LinkedHashMap<String, Object>();
        lexical.put("using", LEXICAL);
        lexical.put("query", sparse);
        lexical.put("limit", lexicalCandidates > 0 ? lexicalCandidates : limit);
        if (where != null) lexical.put("filter", filter(where));

        var body = new LinkedHashMap<String, Object>();
        switch (hybridStrategy) {
            case DENSE -> body.putAll(dense);
            case SPARSE -> body.putAll(lexical);
            case RRF, DBSF -> {
                body.put("prefetch", List.of(dense, lexical));
                body.put("query", hybridStrategy == HybridStrategy.RRF
                        ? Map.of("rrf", Map.of("k", rrfK)) : Map.of("fusion", "dbsf"));
            }
        }
        body.put("limit", limit);
        body.put("with_payload", true);
        if (where != null) body.put("filter", filter(where));
        var points = rows(object(call("POST", path(name) + "/points/query", body)).get("points"));
        var result = columns(points, List.of("documents", "metadatas"));
        // Preserve one score contract for all native benchmark strategies.
        result.put("distances", java.util.Collections.nCopies(points.size(), 1.0));
        result.replaceAll((key, value) -> List.of(value));
        return result;
    }

    private static Map<String, Object> queryColumns(List<Map<String, Object>> points) {
        var result = columns(points, List.of("documents", "metadatas"));
        result.put("distances", points.stream()
                .map(p -> 1 - Math.clamp(((Number) p.get("score")).doubleValue(), -1.0, 1.0)).toList());
        result.replaceAll((key, value) -> List.of(value));
        return result;
    }

    Map<String, Object> get(String name, Map<String, Object> body) {
        List<?> include = (List<?>) body.get("include");
        if (body.get("ids") instanceof List<?> ids) {
            return columns(pointsById(name, ids.stream().map(String.class::cast).toList(), include), include);
        }
        int skip = ((Number) body.getOrDefault("offset", 0)).intValue();
        int limit = ((Number) body.getOrDefault("limit", PAGE)).intValue();
        var where = object(body.get("where"));
        var documentFilter = object(body.get("where_document"));
        String needle = (String) documentFilter.get("$contains");
        Map<String, Object> nativeFilter = filter(where);
        if (needle != null) {
            // In Qdrant 1.16.3, text matching on the unindexed document field is case-sensitive substring matching.
            nativeFilter = Map.of("must", List.of(nativeFilter, Map.of("key", "document", "match", Map.of("text", needle))));
        }
        long generation = mutation.get();
        Cursor previous = cursor.get();
        Object offset = null;
        if (needle == null && previous != null && previous.generation() == generation
                && previous.collection().equals(name) && previous.where().equals(where) && previous.nextIndex() == skip) {
            offset = previous.nextId();
            skip = 0;
        }
        cursor.remove();
        var found = new ArrayList<Map<String, Object>>();
        while (found.size() < limit) {
            int requested = (int) Math.min(PAGE, (long) skip + limit - found.size());
            var query = new HashMap<String, Object>(Map.of("limit", requested, "with_payload", payloadFields(include)));
            if (offset != null) query.put("offset", offset);
            if (needle != null || !where.isEmpty()) query.put("filter", nativeFilter);
            var result = object(call("POST", path(name) + "/points/scroll", query));
            for (var point : rows(result.get("points"))) {
                if (skip > 0) skip--;
                else found.add(point);
            }
            offset = result.get("next_page_offset");
            if (offset == null) break;
        }
        if (offset != null && needle == null) {
            cursor.set(new Cursor(name, where, ((Number) body.getOrDefault("offset", 0)).intValue() + found.size(),
                    offset, generation));
        }
        return columns(found, include);
    }

    int count(String name) {
        return countAt(path(name), Map.of());
    }

    int deleteWhere(String name, Map<String, Object> where) {
        Map<String, Object> condition = Map.of("filter", filter(where));
        int count = countAt(path(name), condition);
        if (count != 0) deleteAt(name, condition);
        return count;
    }

    void deleteIds(String name, List<String> ids) {
        if (!ids.isEmpty()) deleteAt(name, Map.of("points", ids.stream().map(QdrantStore::pointId).toList()));
    }

    private void deleteAt(String name, Map<String, Object> selection) {
        call("POST", path(name) + "/points/delete?wait=true", selection);
        mutation.incrementAndGet();
    }

    private int countAt(String path, Map<String, Object> condition) {
        var body = new HashMap<String, Object>(condition);
        body.put("exact", true);
        return ((Number) object(call("POST", path + "/points/count", body)).get("count")).intValue();
    }

    private synchronized boolean ensureDimensions(String name, int size) {
        var params = collectionParams(name);
        int existing = dimension(params);
        if (existing == size) return hasSparse(params);
        if (existing != 0) throw new IllegalArgumentException("Qdrant vector dimension mismatch; reindex the collection");
        String physical = create(object(info(name).get("metadata")), size);
        switchAlias(alias(name), backing(alias(name)), physical);
        sparseCollections.put(name, nativeHybrid);
        return nativeHybrid;
    }

    private Map<String, Object> collectionParams(String name) {
        var result = object(call("GET", path(name), null));
        return object(object(result.get("config")).get("params"));
    }

    private int dimension(String name) {
        return dimension(collectionParams(name));
    }

    private static boolean hasSparse(Map<String, Object> params) {
        return object(params.get("sparse_vectors")).containsKey(LEXICAL);
    }

    private static int dimension(Map<String, Object> params) {
        return ((Number) object(params.get("vectors")).getOrDefault("size", 0)).intValue();
    }

    private String create(Map<String, Object> stamp, int size) {
        String physical = "spectra_points_" + UUID.randomUUID().toString().replace("-", "");
        String path = "/collections/" + physical;
        var vectors = new LinkedHashMap<String, Object>();
        if (size > 0) {
            vectors.put("size", size);
            vectors.put("distance", "Cosine");
            if (int8) vectors.put("on_disk", true);
        }
        var body = new LinkedHashMap<String, Object>(
                Map.of("vectors", vectors, "metadata", stamp, "hnsw_config", hnswConfig()));
        if (size > 0 && int8) {
            body.put("quantization_config", Map.of("scalar", Map.of("type", "int8", "always_ram", true)));
        }
        if (size > 0 && nativeHybrid) body.put("sparse_vectors", Map.of(LEXICAL, Map.of("modifier", "idf")));
        call("PUT", path, body);
        if (size != 0) {
            for (String key : List.of("sha256", "sourceFile", "chunkIndex", "zipEntry")) {
                call("PUT", path + "/index?wait=true", Map.of("field_name", "metadata." + key, "field_schema", "keyword"));
            }
        }
        return physical;
    }

    private void switchAlias(String alias, String old, String next) {
        var actions = new ArrayList<Map<String, Object>>();
        if (old != null) actions.add(Map.of("delete_alias", Map.of("alias_name", alias)));
        actions.add(Map.of("create_alias", Map.of("alias_name", alias, "collection_name", next)));
        call("POST", "/collections/aliases", Map.of("actions", actions));
        if (old != null && old.startsWith("spectra_points_")) {
            try {
                call("DELETE", "/collections/" + old, null);
            } catch (RuntimeException e) {
                log.warn("Unable to clean up Qdrant collection {}", old, e);
            }
        }
    }

    private List<Map<String, Object>> aliases() {
        return rows(object(call("GET", "/aliases", null)).get("aliases"));
    }

    private String backing(String alias) {
        return aliases().stream().filter(a -> alias.equals(a.get("alias_name")))
                .map(a -> (String) a.get("collection_name")).findFirst().orElse(null);
    }

    private List<Map<String, Object>> pointsById(String name, List<String> ids, List<?> include) {
        if (ids.isEmpty()) return List.of();
        return rows(call("POST", path(name) + "/points",
                Map.of("ids", ids.stream().map(QdrantStore::pointId).toList(), "with_payload", payloadFields(include))));
    }

    private static List<String> payloadFields(List<?> include) {
        var fields = new ArrayList<String>();
        fields.add("chunkId");
        if (include.contains("documents")) fields.add("document");
        if (include.contains("metadatas")) fields.add("metadata");
        return fields;
    }

    private static Map<String, Object> columns(List<Map<String, Object>> points, List<?> include) {
        var payloads = points.stream().map(point -> object(point.get("payload"))).toList();
        var columns = new LinkedHashMap<String, Object>();
        columns.put("ids", payloads.stream().map(p -> p.get("chunkId")).toList());
        if (include.contains("documents")) columns.put("documents", payloads.stream().map(p -> p.get("document")).toList());
        if (include.contains("metadatas")) columns.put("metadatas", payloads.stream().map(p -> object(p.get("metadata"))).toList());
        return columns;
    }

    private static Map<String, Object> filter(Map<String, Object> where) {
        var clauses = new ArrayList<Map<String, Object>>();
        where.forEach((key, value) -> {
            if ("$and".equals(key) || "$or".equals(key)) {
                clauses.add(Map.of("$and".equals(key) ? "must" : "should", rows(value).stream()
                        .map(QdrantStore::filter).toList()));
            } else if ("$is_empty".equals(key)) {
                clauses.add(Map.of("is_empty", Map.of("key", "metadata." + value)));
            } else {
                var operation = object(value);
                var match = operation.containsKey("$in") ? Map.of("any", operation.get("$in"))
                        : Map.of("value", operation.getOrDefault("$eq", value));
                clauses.add(Map.of("key", "metadata." + key, "match", match));
            }
        });
        return Map.of("must", clauses);
    }

    private void upsert(String path, List<Map<String, Object>> points) {
        call("PUT", path + "/points?wait=true", Map.of("points", points));
    }

    private Object call(String method, String path, Object body) {
        var request = http.method(HttpMethod.valueOf(method)).uri(path);
        String operation = path.endsWith("/points/query/groups") ? "groups"
                : path.endsWith("/points/query/batch") ? "batch"
                : path.endsWith("/points/query") ? (body instanceof Map<?, ?> m && m.containsKey("prefetch") ? "hybrid" : "query")
                : path.endsWith("/points/scroll") ? "scroll" : "other";
        long start = System.nanoTime();
        byte[] bytes;
        try {
            bytes = (body == null ? request : request.bodyValue(body))
                    .retrieve().bodyToMono(byte[].class).block(TIMEOUT);
        } finally {
            meters.timer("spectra.qdrant.http", "operation", operation)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
        if (bytes == null) throw new IllegalStateException("Empty Qdrant response");
        meters.summary("spectra.qdrant.response.bytes", "operation", operation).record(bytes.length);
        Map<?, ?> response;
        try {
            response = mapper.readValue(bytes, Map.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unable to decode Qdrant response", e);
        }
        if (response != null && response.get("time") instanceof Number seconds
                && Double.isFinite(seconds.doubleValue()) && seconds.doubleValue() >= 0) {
            meters.timer("spectra.qdrant.engine", "operation", operation)
                    .record((long) (seconds.doubleValue() * 1_000_000_000), TimeUnit.NANOSECONDS);
        }
        if (response == null || !"ok".equals(response.get("status")) || !response.containsKey("result")) {
            throw new IllegalStateException("Invalid Qdrant response for " + method + " " + path);
        }
        return response.get("result");
    }

    private static Map<String, Object> point(String id, List<Float> vector, Map<String, Object> payload,
                                             boolean sparse) {
        Object vectors = sparse
                ? Map.of(DENSE, vector, LEXICAL, sparseVector((String) payload.get("document"), true))
                : vector;
        return Map.of("id", pointId(id), "vector", vectors, "payload", payload);
    }

    /** Fixed-reference BM25 term weights; Qdrant supplies the collection IDF at query time. */
    private static Map<String, Object> sparseVector(String text, boolean document) {
        // Requête : mêmes termes que le BM25 Java (sans mots-outils de question), pour que la comparaison
        // des deux fusions ne porte pas sur le prétraitement.
        var frequencies = document ? BM25Index.buildTermFreq(text) : BM25Index.buildQueryTerms(text);
        int length = frequencies.values().stream().mapToInt(Integer::intValue).sum();
        var weighted = new java.util.TreeMap<Integer, Double>();
        frequencies.forEach((term, frequency) -> {
            double value = document ? 2.5 * frequency / (frequency + 1.5 * (0.25 + 0.75 * length / 120.0)) : 1;
            var hash = UUID.nameUUIDFromBytes(term.getBytes(StandardCharsets.UTF_8));
            int index = (int) hash.getLeastSignificantBits() & Integer.MAX_VALUE;
            weighted.merge(index, value, Math::max);
        });
        return Map.of("indices", List.copyOf(weighted.keySet()), "values", List.copyOf(weighted.values()));
    }

    private static String pointId(String id) {
        return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String path(String name) {
        return "/collections/" + alias(name);
    }

    private static String alias(String name) {
        if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{1,61}[a-zA-Z0-9]")) {
            throw new IllegalArgumentException("Invalid Qdrant collection name");
        }
        return PREFIX + name;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        if (!(value instanceof List<?>)) throw new IllegalStateException("Invalid Qdrant points or aliases");
        return (List<Map<String, Object>>) value;
    }

    private record Cursor(String collection, Map<String, Object> where, int nextIndex, Object nextId, long generation) {}
}
