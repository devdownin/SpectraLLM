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
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final WebClient http;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final String baseUrl;
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
            points.add(point(id, embeddings.get(i), payload));
        }
        upsert("/collections/" + staging, points);
    }

    void add(String name, List<String> ids, List<String> texts, List<Map<String, String>> metadata,
             List<List<Float>> embeddings) {
        if (ids.isEmpty()) return;
        if (ids.size() != embeddings.size()) throw new IllegalArgumentException("Missing chunk embeddings");
        ensureDimensions(name, embeddings.getFirst().size());
        var points = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            points.add(point(id, embeddings.get(i),
                    Map.of("chunkId", id, "document", texts.get(i), "metadata", metadata.get(i))));
        }
        upsert(path(name), points);
        mutation.incrementAndGet();
    }

    Map<String, Object> query(String name, List<Float> embedding, int limit, Map<String, Object> where) {
        var body = new LinkedHashMap<String, Object>(Map.of("query", embedding, "limit", limit, "with_payload", true));
        if (where != null) body.put("filter", filter(where));
        List<Map<String, Object>> points;
        try {
            points = rows(object(call("POST", path(name) + "/points/query", body)).get("points"));
        } catch (WebClientResponseException.BadRequest e) {
            if (dimension(name) != 0) throw e;
            points = List.of();
        }
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

    private synchronized void ensureDimensions(String name, int size) {
        int existing = dimension(name);
        if (existing == size) return;
        if (existing != 0) throw new IllegalArgumentException("Qdrant vector dimension mismatch; reindex the collection");
        String physical = create(object(info(name).get("metadata")), size);
        switchAlias(alias(name), backing(alias(name)), physical);
    }

    private int dimension(String name) {
        var result = object(call("GET", path(name), null));
        var config = object(result.get("config"));
        var vectors = object(object(config.get("params")).get("vectors"));
        return ((Number) vectors.getOrDefault("size", 0)).intValue();
    }

    private String create(Map<String, Object> stamp, int size) {
        String physical = "spectra_points_" + UUID.randomUUID().toString().replace("-", "");
        String path = "/collections/" + physical;
        var vectors = size == 0 ? Map.of() : Map.of("size", size, "distance", "Cosine");
        call("PUT", path, Map.of("vectors", vectors, "metadata", stamp, "hnsw_config", Map.of("ef_construct", 200)));
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
        String operation = path.endsWith("/points/query") ? "query"
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

    private static Map<String, Object> point(String id, List<Float> vector, Map<String, Object> payload) {
        return Map.of("id", pointId(id), "vector", vector, "payload", payload);
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
