package fr.spectra.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Normalized first query row, with provenance and scores aligned to retained documents. */
record ChromaQueryResult(List<String> documents, List<Map<String, String>> metadatas,
                         List<Double> distances, List<String> ids) {
    // Same sentinel as BM25-only results: no claim of a perfect vector match.
    static final double UNKNOWN_DISTANCE = 1.0;

    static ChromaQueryResult from(Map<String, Object> response) {
        List<?> docs = firstRow(response, "documents");
        List<?> metas = firstRow(response, "metadatas");
        List<?> dists = firstRow(response, "distances");
        List<?> ids = firstRow(response, "ids");
        List<String> texts = new ArrayList<>();
        List<Map<String, String>> metadata = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        List<String> identifiers = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            if (!(docs.get(i) instanceof String text) || text.isBlank()) continue;
            texts.add(text);
            Map<String, String> meta = new LinkedHashMap<>();
            if (at(metas, i) instanceof Map<?, ?> raw) {
                raw.forEach((key, value) -> {
                    if (key instanceof String k && value instanceof String v) meta.put(k, v);
                });
            }
            metadata.add(Map.copyOf(meta));
            Object distance = at(dists, i);
            double value = distance instanceof Number number ? number.doubleValue() : UNKNOWN_DISTANCE;
            distances.add(Double.isFinite(value) && value >= 0 ? value : UNKNOWN_DISTANCE);
            identifiers.add(at(ids, i) instanceof String id && !id.isBlank() ? id : "__vec_" + i);
        }
        return new ChromaQueryResult(List.copyOf(texts), List.copyOf(metadata),
                List.copyOf(distances), List.copyOf(identifiers));
    }

    private static List<?> firstRow(Map<String, Object> response, String key) {
        if (response != null && response.get(key) instanceof List<?> rows
                && !rows.isEmpty() && rows.getFirst() instanceof List<?> row) return row;
        return List.of();
    }

    private static Object at(List<?> list, int index) {
        return index < list.size() ? list.get(index) : null;
    }
}
