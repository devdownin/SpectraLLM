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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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

    // Timeouts adaptés à chaque opération
    private static final Duration TIMEOUT_DEFAULT  = Duration.ofSeconds(10);
    private static final Duration TIMEOUT_ADD      = Duration.ofSeconds(60);
    private static final Duration TIMEOUT_QUERY    = Duration.ofSeconds(15);
    private static final Duration TIMEOUT_BULK_GET = Duration.ofSeconds(30);

    // ChromaDB : 3-63 chars, alphanumeric + . _ -, sans double tiret, sans . en début/fin
    private static final Pattern COLLECTION_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{1,61}[a-zA-Z0-9]$");

    private static final String TENANT   = "default_tenant";
    private static final String DATABASE = "default_database";

    private static final String COLLECTIONS_BASE =
            "/api/v2/tenants/" + TENANT + "/databases/" + DATABASE + "/collections";

    private final WebClient webClient;
    private final SpectraProperties.ChromaDbProperties props;

    // P1 — Cache name → collectionId pour éviter un POST ChromaDB à chaque appel
    private final ConcurrentHashMap<String, String> collectionIdCache = new ConcurrentHashMap<>();

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
                    .block(TIMEOUT_DEFAULT);
            long elapsed = System.currentTimeMillis() - start;
            return new ServiceStatus("chromadb", props.baseUrl(), true, "ok", elapsed, Map.of());
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("ChromaDB indisponible: {}", e.getMessage());
            return ServiceStatus.unavailable("chromadb", props.baseUrl(), elapsed);
        }
    }

    /**
     * Crée une collection si elle n'existe pas et retourne son ID.
     * L'ID est mis en cache pour éviter un aller-retour réseau à chaque appel.
     */
    @SuppressWarnings("unchecked")
    public String getOrCreateCollection(String name) {
        // S1 — Validation du nom avant tout appel ChromaDB
        validateCollectionName(name);

        // P1 — Cache hit
        String cached = collectionIdCache.get(name);
        if (cached != null) return cached;

        Map<String, Object> body = Map.of("name", name, "get_or_create", true);

        Map<String, Object> response = webClient.post()
                .uri(COLLECTIONS_BASE)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(TIMEOUT_DEFAULT);

        // B1 — Null-guard explicite
        if (response == null) {
            throw new IllegalStateException("ChromaDB: réponse vide lors de getOrCreateCollection('" + name + "')");
        }

        String id = (String) response.get("id");
        if (id == null) {
            throw new IllegalStateException("ChromaDB: champ 'id' absent de la réponse pour la collection '" + name + "'");
        }

        collectionIdCache.put(name, id);
        return id;
    }

    /**
     * Ajoute des chunks avec leurs embeddings dans une collection.
     * Utilise un timeout étendu (60s) pour absorber les grands batches.
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
                .block(TIMEOUT_ADD);  // P3 — timeout étendu pour grands batches

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
                .block(TIMEOUT_QUERY);  // P3 — timeout étendu pour grandes collections
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
                .block(TIMEOUT_BULK_GET);
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
                .block(TIMEOUT_BULK_GET);
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
     * Utilise le filtre {@code where} ChromaDB pour ne récupérer que les IDs concernés
     * au lieu de charger toute la collection en mémoire.
     */
    @SuppressWarnings("unchecked")
    public int deleteBySource(String collectionId, String sourceFile) {
        // P2 — filtre where : seuls les IDs du fichier source sont récupérés
        Map<String, Object> body = Map.of(
                "where",   Map.of("sourceFile", Map.of("$eq", sourceFile)),
                "include", List.of()  // IDs uniquement, pas de documents ni métadonnées
        );

        Map<String, Object> result = webClient.post()
                .uri(COLLECTIONS_BASE + "/{id}/get", collectionId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(TIMEOUT_BULK_GET);

        if (result == null) return 0;
        List<String> ids = (List<String>) result.get("ids");
        if (ids == null || ids.isEmpty()) return 0;

        webClient.post()
                .uri(COLLECTIONS_BASE + "/{id}/delete", collectionId)
                .bodyValue(Map.of("ids", ids))
                .retrieve()
                .bodyToMono(Void.class)
                .block(TIMEOUT_DEFAULT);

        return ids.size();
    }

    /**
     * Compte le nombre de documents dans une collection.
     */
    public int count(String collectionId) {
        Integer count = webClient.get()
                .uri(COLLECTIONS_BASE + "/{id}/count", collectionId)
                .retrieve()
                .bodyToMono(Integer.class)
                .block(TIMEOUT_DEFAULT);
        return count != null ? count : 0;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void validateCollectionName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Le nom de collection ChromaDB ne peut pas être vide");
        }
        if (name.length() < 3 || name.length() > 63) {
            throw new IllegalArgumentException(
                    "Le nom de collection ChromaDB doit faire entre 3 et 63 caractères : '" + name + "'");
        }
        if (!COLLECTION_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Le nom de collection ChromaDB contient des caractères invalides : '" + name + "'. "
                    + "Seuls les caractères alphanumériques, '.', '_' et '-' sont autorisés.");
        }
    }
}
