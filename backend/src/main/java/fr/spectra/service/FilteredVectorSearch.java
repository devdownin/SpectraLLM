package fr.spectra.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Enforces document identity after Chroma's deliberately broad legacy-compatible prefilter. */
final class FilteredVectorSearch {
    // Bound work even when many excluded homonyms precede the permitted documents.
    static final int MAX_CANDIDATES = 4096;

    private FilteredVectorSearch() {}

    static Map<String, Object> query(ChromaDbClient client, String collectionId, List<Float> embedding,
                                     int topN, RetrievalFilterResolver.ResolvedFilter filter) {
        if (!filter.active()) return client.query(collectionId, embedding, topN);
        if (filter.matchesNothing()) return Map.of();
        int requested = Math.min(topN, MAX_CANDIDATES);
        while (true) {
            ChromaQueryResult raw = ChromaQueryResult.from(
                    client.query(collectionId, embedding, requested, filter.chromaWhere()));
            List<String> texts = new ArrayList<>();
            List<Map<String, String>> metas = new ArrayList<>();
            List<Double> distances = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < raw.documents().size() && texts.size() < topN; i++) {
                Map<String, String> meta = raw.metadatas().get(i);
                if (!filter.accepts(meta.get("sha256"), meta.get("sourceFile"))) continue;
                texts.add(raw.documents().get(i));
                metas.add(meta);
                distances.add(raw.distances().get(i));
                ids.add(raw.ids().get(i));
            }
            if (texts.size() >= topN || raw.documents().size() < requested || requested == MAX_CANDIDATES) {
                return Map.of("documents", List.of(texts), "metadatas", List.of(metas),
                        "distances", List.of(distances), "ids", List.of(ids));
            }
            requested = Math.min(MAX_CANDIDATES, requested * 2);
        }
    }
}
