package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.ServiceStatus;
import fr.spectra.model.TextChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Client ChromaDB — API v2.
 *
 * Toutes les requêtes passent par :
 *   /api/v2/tenants/{tenant}/databases/{database}/collections/…
 *
 * L'API v1 (/api/v1/) a été supprimée (HTTP 410) à partir de ChromaDB 1.x.
 */
@Service
public class ChromaDbClient {

    private static final Logger log = LoggerFactory.getLogger(ChromaDbClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final String TENANT   = "default_tenant";
    private static final String DATABASE = "default_database";

    /** Préfixe commun à toutes les opérations sur les collections. */
    private static final String COLLECTIONS_BASE =
            "/api/v2/tenants/" + TENANT + "/databases/" + DATABASE + "/collections";

    private final WebClient webClient;
    private final SpectraProperties.ChromaDbProperties props;

    public ChromaDbClient(@Qualifier("chromaDbWebClient") WebClient webClient,
                          SpectraProperties properties) {
        this.webClient = webClient;
        this.props = properties.chromadb();
    }

    public ServiceStatus checkHealth() {
        long start = System.currentTimeMillis();
        try {
            webClient.get()
                    .uri("/api/v2/heartbeat")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(TIMEOUT);
            long elapsed = System.currentTimeMillis() - start;
            return new ServiceStatus("chromadb", props.baseUrl(), true, "ok", elapsed, Map.of());
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("ChromaDB indisponible: {}", e.getMessage());
            return ServiceStatus.unavailable("chromadb", props.baseUrl(), elapsed);
        }
    }

    /**
     * Crée une collection si elle n'existe pas, et retourne son ID.
     */
    @SuppressWarnings("unchecked")
    public String getOrCreateCollection(String name) {
        Map<String, Object> body = Map.of("name", name, "get_or_create", true);

        Map<String, Object> response = webClient.post()
                .uri(COLLECTIONS_BASE)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(TIMEOUT);

        return (String) response.get("id");
    }

    /**
     * Ajoute des chunks avec leurs embeddings dans une collection.
     */
    public void addDocuments(String collectionId, List<TextChunk> chunks, List<List<Float>> embeddings) {
        List<String> ids        = chunks.stream().map(TextChunk::id).toList();
        List<String> documents  = chunks.stream().map(TextChunk::text).toList();
        List<Map<String, String>> metadatas = chunks.stream().map(TextChunk::metadata).toList();

        Map<String, Object> body = Map.of(
                "ids",        ids,
                "documents",  documents,
                "embeddings", embeddings,
                "metadatas",  metadatas
        );

        webClient.post()
                .uri(COLLECTIONS_BASE + "/{id}/add", collectionId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class)
                .block(TIMEOUT);

        log.info("Ajouté {} chunks dans la collection {}", chunks.size(), collectionId);
    }

    /**
     * Recherche les chunks les plus similaires à un vecteur de requête.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> query(String collectionId, List<Float> queryEmbedding, int nResults) {
        Map<String, Object> body = Map.of(
                "query_embeddings", List.of(queryEmbedding),
                "n_results",        nResults,
                "include",          List.of("documents", "metadatas", "distances")
        );

        return webClient.post()
                .uri(COLLECTIONS_BASE + "/{id}/query", collectionId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(TIMEOUT);
    }

    /**
     * Récupère tous les documents d'une collection (sans embeddings).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAllDocuments(String collectionId) {
        Map<String, Object> body = Map.of("include", List.of("documents", "metadatas"));

        return webClient.post()
                .uri(COLLECTIONS_BASE + "/{id}/get", collectionId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(30));
    }

    /**
     * Récupère une page de documents avec ids, textes et métadonnées (pour FTS rebuild).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDocumentsPaged(String collectionId, int limit, int offset) {
        Map<String, Object> body = Map.of(
                "limit",   limit,
                "offset",  offset,
                "include", List.of("documents", "metadatas")
        );

        return webClient.post()
                .uri(COLLECTIONS_BASE + "/{id}/get", collectionId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(30));
    }

    /**
     * Liste les fichiers sources dans une collection avec leur nombre de chunks.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Integer> listSources(String collectionId) {
        Map<String, Object> result = getAllDocuments(collectionId);
        List<Map<String, String>> metadatas =
                (List<Map<String, String>>) result.get("metadatas");
        Map<String, Integer> sources = new java.util.LinkedHashMap<>();
        if (metadatas != null) {
            for (Map<String, String> meta : metadatas) {
                String source = meta.getOrDefault("sourceFile", "unknown");
                sources.merge(source, 1, Integer::sum);
            }
        }
        return sources;
    }

    /**
     * Supprime tous les chunks correspondant à un fichier source.
     * Retourne le nombre de chunks supprimés.
     */
    @SuppressWarnings("unchecked")
    public int deleteBySource(String collectionId, String sourceFile) {
        Map<String, Object> all = getAllDocuments(collectionId);
        List<String> ids = (List<String>) all.get("ids");
        List<Map<String, String>> metadatas = (List<Map<String, String>>) all.get("metadatas");

        if (ids == null || metadatas == null) return 0;

        List<String> toDelete = new java.util.ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            if (sourceFile.equals(metadatas.get(i).get("sourceFile"))) {
                toDelete.add(ids.get(i));
            }
        }

        if (!toDelete.isEmpty()) {
            webClient.post()
                    .uri(COLLECTIONS_BASE + "/{id}/delete", collectionId)
                    .bodyValue(Map.of("ids", toDelete))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block(TIMEOUT);
        }
        return toDelete.size();
    }

    /**
     * Compte le nombre de documents dans une collection.
     */
    public int count(String collectionId) {
        Integer count = webClient.get()
                .uri(COLLECTIONS_BASE + "/{id}/count", collectionId)
                .retrieve()
                .bodyToMono(Integer.class)
                .block(TIMEOUT);
        return count != null ? count : 0;
    }
}
