package fr.spectra.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "spectra.qdrant.pilot-enabled", havingValue = "true")
class QdrantRetrievalPilot {
    private final WebClient client;
    private final StreamVersionVisibility visibility;
    private final String sourceId;
    private final String target;

    QdrantRetrievalPilot(WebClient.Builder builder, StreamVersionVisibility visibility,
                         @Value("${spectra.qdrant.base-url:http://qdrant-pilot:6333}") String baseUrl,
                         @Value("${spectra.qdrant.api-key:}") String apiKey,
                         @Value("${spectra.qdrant.source-id:}") String sourceId,
                         @Value("${spectra.qdrant.target:}") String target) {
        this.visibility = visibility;
        this.sourceId = UUID.fromString(sourceId).toString();
        if (!target.matches("spectra_pilot_[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException("Qdrant pilot target must start with spectra_pilot_");
        }
        this.target = target;
        WebClient.Builder configured = builder.clone().baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024));
        if (!apiKey.isBlank()) configured.defaultHeader("api-key", apiKey);
        this.client = configured.build();
    }

    boolean serves(String collectionId) {
        return sourceId.equals(collectionId);
    }

    Map<String, Object> query(List<Float> embedding, int topN, RetrievalFilterResolver.ResolvedFilter filter) {
        if (topN <= 0 || filter.matchesNothing()) return Map.of();

        var visible = visibility.snapshot();
        int limit = Math.min(topN, FilteredVectorSearch.MAX_CANDIDATES);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", embedding);
        body.put("with_payload", true);
        body.put("with_vector", false);
        if (filter.active()) body.put("filter", prefilter(filter));

        while (true) {
            body.put("limit", limit);
            QueryResponse response = Objects.requireNonNull(client.post()
                    .uri("/collections/{name}/points/query", target)
                    .bodyValue(body).retrieve().bodyToMono(QueryResponse.class)
                    .block(Duration.ofSeconds(5)), "Empty Qdrant response");
            List<Point> points = response.result().points();
            List<String> ids = new ArrayList<>();
            List<String> documents = new ArrayList<>();
            List<Map<String, Object>> metadata = new ArrayList<>();
            List<Double> distances = new ArrayList<>();
            for (Point point : points) {
                Payload payload = point.payload();
                if (!sourceId.equals(payload.sourceCollectionId())) {
                    throw new IllegalStateException("Qdrant pilot snapshot belongs to a different Chroma collection");
                }
                Map<String, Object> meta = payload.metadata();
                String hash = meta.get("sha256") instanceof String value ? value : null;
                String source = meta.get("sourceFile") instanceof String value ? value : null;
                if (payload.document().isBlank() || !filter.accepts(hash, source) || !visible.test(hash, source)) continue;
                ids.add(payload.chunkId());
                documents.add(payload.document());
                metadata.add(meta);
                distances.add(1 - Math.clamp(point.score(), -1.0, 1.0));
                if (ids.size() == topN) break;
            }
            if (ids.size() >= topN || points.size() < limit || limit == FilteredVectorSearch.MAX_CANDIDATES) {
                return Map.of("ids", List.of(ids), "documents", List.of(documents),
                        "metadatas", List.of(metadata), "distances", List.of(distances));
            }
            limit = Math.min(FilteredVectorSearch.MAX_CANDIDATES, limit * 2);
        }
    }

    private static Map<String, Object> prefilter(RetrievalFilterResolver.ResolvedFilter filter) {
        List<Map<String, Object>> alternatives = new ArrayList<>();
        if (filter.sha256s() != null && !filter.sha256s().isEmpty()) {
            alternatives.add(Map.of("key", "metadata.sha256", "match", Map.of("any", filter.sha256s())));
        }
        if (filter.sourceFiles() != null && !filter.sourceFiles().isEmpty()) {
            Map<String, Object> bySource = Map.of("key", "metadata.sourceFile",
                    "match", Map.of("any", filter.sourceFiles()));
            if (filter.sha256s() != null) {
                bySource = Map.of("must", List.of(bySource,
                        Map.of("is_empty", Map.of("key", "metadata.sha256"))));
            }
            alternatives.add(bySource);
        }
        return Map.of("should", alternatives);
    }

    record QueryResponse(Result result) {}
    record Result(List<Point> points) {}
    record Point(double score, Payload payload) {}
    record Payload(String sourceCollectionId, String chunkId, String document, Map<String, Object> metadata) {}
}
