package fr.spectra.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Recherche de chunks par sous-chaîne exacte, dans le périmètre du filtre de documents.
 *
 * <p>Même contrat que {@link FilteredVectorSearch} : le préfiltre ChromaDB est volontairement
 * large, l'identité du document est donc revérifiée ici ({@code ResolvedFilter#accepts}). Un
 * filtre qui n'admet aucun document ne produit aucune requête.</p>
 */
final class ExactMatchRetriever {

    private ExactMatchRetriever() {}

    static List<ChromaDbClient.ChunkHit> find(ChromaDbClient client, String collectionId, String needle,
                                              RetrievalFilterResolver.ResolvedFilter filter, int limit) {
        if (filter.matchesNothing()) return List.of();
        // Marge : le postfiltre peut écarter des homonymes que le préfiltre a laissé passer.
        int fetch = filter.active() ? Math.min(limit * 4, FilteredVectorSearch.MAX_CANDIDATES) : limit;
        List<ChromaDbClient.ChunkHit> accepted = new ArrayList<>();
        for (ChromaDbClient.ChunkHit hit : client.findChunksContaining(collectionId, needle,
                "qdrant".equals(client.vectorEngine()) ? filter.qdrantWhere() : filter.chromaWhere(), fetch)) {
            Map<String, String> meta = hit.metadata();
            if (!filter.accepts(meta.get("sha256"), meta.get("sourceFile"))) continue;
            accepted.add(hit);
            if (accepted.size() >= limit) break;
        }
        return accepted;
    }
}
