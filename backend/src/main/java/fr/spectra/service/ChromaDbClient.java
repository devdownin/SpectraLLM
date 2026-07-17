package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.ServiceStatus;
import fr.spectra.model.TextChunk;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Client ChromaDB — la base vectorielle qui rend la recherche sémantique possible.
 *
 * <p><b>Idée clé.</b> Chaque chunk de document est stocké non pas comme du texte, mais comme
 * un <i>embedding</i> : un vecteur de plusieurs centaines de dimensions où la <i>proximité
 * géométrique</i> reflète la <i>proximité de sens</i>. Chercher devient alors un calcul de
 * distance : on encode la question dans le même espace, et ChromaDB renvoie les vecteurs les
 * plus proches via un index ANN (HNSW). On retrouve ainsi des passages pertinents même quand
 * ils ne partagent <b>aucun mot</b> avec la question — ce qu'une recherche par mots-clés
 * (BM25) ne sait pas faire.</p>
 *
 * <p><b>Pourquoi le cosinus.</b> Les collections sont créées avec une configuration HNSW
 * explicite ({@code space=cosine}) plutôt que la distance L2 par défaut. Les embeddings de
 * llama.cpp étant normalisés, la similarité cosinus donne des scores interprétables sur
 * {@code [0,1]} — exploités pour afficher un score par source et pour le re-ranking.
 * <b>Attention :</b> l'espace de distance est figé à la création de la collection ; basculer
 * une collection existante en cosinus impose une ré-ingestion. La création est rendue robuste
 * à la version de ChromaDB (API 1.x → repli métadonnées {@code hnsw:*} → création simple).</p>
 *
 * <p><b>Cohérence embedding ↔ index.</b> Les vecteurs d'une collection ne sont comparables
 * qu'entre eux : changer de modèle d'embedding sans ré-ingérer rend silencieusement le RAG
 * inutilisable (les distances n'ont plus de sens). Chaque collection est donc estampillée à
 * sa création avec le modèle d'embedding actif ({@code spectra:embedding-model}) et chaque
 * accès vérifie que le modèle actif correspond toujours à l'estampille — sinon
 * {@link EmbeddingModelMismatchException} est levée avec la marche à suivre (réindexation).
 * Les collections créées avant cette protection (sans estampille) restent accessibles avec
 * un simple avertissement.</p>
 *
 * <p><b>Note d'implémentation.</b> Toutes les requêtes passent par l'API v2 :
 * {@code /api/v2/tenants/{tenant}/databases/{database}/collections/…}.
 * L'API v1 ({@code /api/v1/}) a été supprimée (HTTP 410) à partir de ChromaDB 1.x.</p>
 */
@Service
public class ChromaDbClient {

    private static final Logger log = LoggerFactory.getLogger(ChromaDbClient.class);

    /** Clé de métadonnée portant le modèle d'embedding qui a indexé la collection. */
    public static final String EMBEDDING_MODEL_METADATA_KEY = "spectra:embedding-model";

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
    private final ModelRegistryService modelRegistry;

    // P1 — Cache name → (id, estampille embedding) pour éviter un POST ChromaDB à chaque appel
    private final ConcurrentHashMap<String, CollectionRef> collectionCache = new ConcurrentHashMap<>();
    // Collections legacy (sans estampille) déjà signalées, pour ne logger l'avertissement qu'une fois
    private final java.util.Set<String> warnedUnstamped = ConcurrentHashMap.newKeySet();

    public ChromaDbClient(@Qualifier("chromaDbWebClient") WebClient webClient,
                          SpectraProperties properties,
                          ModelRegistryService modelRegistry) {
        this.webClient = webClient;
        this.props = properties.chromadb();
        this.modelRegistry = modelRegistry;
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
    @CircuitBreaker(name = "chroma", fallbackMethod = "getOrCreateCollectionFallback")
    @Retry(name = "chroma")
    public String getOrCreateCollection(String name) {
        // S1 — Validation du nom avant tout appel ChromaDB
        validateCollectionName(name);

        // P1 — Cache hit (la cohérence embedding est revérifiée à CHAQUE appel :
        // le modèle actif peut changer à chaud via le registre)
        CollectionRef ref = collectionCache.get(name);
        if (ref == null) {
            // Création avec espace cosinus + réglages HNSW (sinon ChromaDB applique L2
            // par défaut) :
            //  - space=cosine : convention RAG, scores de similarité interprétables [0,1]
            //    (les vecteurs de llama.cpp /v1/embeddings sont normalisés)
            //  - ef_search relevé : meilleur recall qu'avec le défaut (~10), un reranker
            //    affine ensuite le top-K
            //  - ef_construction relevé : index plus précis à la construction
            // Le SCHÉMA de configuration dépend de la version de ChromaDB : on tente
            // l'API 1.x (configuration.hnsw), puis on retombe sur les métadonnées
            // hnsw:* (versions plus anciennes), enfin sur une création simple — afin
            // que le cosinus soit appliqué quelle que soit la version sans jamais
            // casser la création.
            // NB : l'espace de distance est figé à la création ; une collection déjà
            // existante conserve sa configuration (get_or_create ne la modifie pas) —
            // l'estampille embedding retournée est donc celle de la création d'origine.
            ref = createCollectionWithCosine(name);
            collectionCache.put(name, ref);
        }

        verifyEmbeddingModel(name, ref.embeddingModel());
        return ref.id();
    }

    /**
     * Vérifie que le modèle d'embedding actif correspond à celui qui a indexé la collection.
     * Bloque lecture ET écriture : interroger un index avec des vecteurs d'un autre modèle
     * renvoie des résultats aléatoires, et y écrire le corrompt définitivement.
     */
    private void verifyEmbeddingModel(String name, String stampedModel) {
        String active = modelRegistry != null ? modelRegistry.getActiveEmbeddingModel() : null;

        if (stampedModel == null || stampedModel.isBlank()) {
            // Collection créée avant l'estampillage : impossible de vérifier, on signale une fois.
            if (warnedUnstamped.add(name)) {
                log.warn("Collection '{}' sans estampille de modèle d'embedding (créée avant cette "
                        + "protection). Impossible de vérifier la cohérence avec le modèle actif '{}' — "
                        + "en cas de doute, ré-ingérez la collection.", name, active);
            }
            return;
        }
        if (active == null || active.isBlank() || active.equals(stampedModel)) {
            return;
        }
        throw new EmbeddingModelMismatchException(
                "La collection '" + name + "' a été indexée avec le modèle d'embedding '" + stampedModel
                + "' mais le modèle actif est '" + active + "'. Les vecteurs ne sont pas comparables : "
                + "réactivez '" + stampedModel + "' ou ré-ingérez la collection avec le nouveau modèle.");
    }

    /** Crée la collection en cosinus selon plusieurs schémas, du plus récent au plus ancien. */
    private CollectionRef createCollectionWithCosine(String name) {
        // Estampille systématiquement le modèle d'embedding actif ; pour une collection
        // déjà existante, get_or_create ignore ces métadonnées et renvoie l'estampille d'origine.
        Map<String, Object> stamp = embeddingStamp();

        // ChromaDB 1.x : objet `configuration.hnsw`
        Map<String, Object> modern = withStamp(Map.of(
                "name", name, "get_or_create", true,
                "configuration", Map.of("hnsw", Map.of(
                        "space", "cosine", "ef_search", 100, "ef_construction", 200))), stamp);
        try {
            return postCreateCollection(name, modern);
        } catch (WebClientResponseException e) {
            if (!e.getStatusCode().is4xxClientError()) throw e;
            log.warn("ChromaDB: 'configuration.hnsw' refusé ({}), repli sur métadonnées hnsw:*",
                    e.getStatusCode());
        }
        // Versions plus anciennes : métadonnées `hnsw:*`
        Map<String, Object> hnswMeta = new java.util.LinkedHashMap<>(Map.of(
                "hnsw:space", "cosine", "hnsw:search_ef", 100, "hnsw:construction_ef", 200));
        hnswMeta.putAll(stamp);
        Map<String, Object> legacy = Map.of(
                "name", name, "get_or_create", true,
                "metadata", hnswMeta);
        try {
            return postCreateCollection(name, legacy);
        } catch (WebClientResponseException e) {
            if (!e.getStatusCode().is4xxClientError()) throw e;
            log.warn("ChromaDB: métadonnées hnsw:* refusées ({}), création sans réglage HNSW "
                    + "(distance par défaut)", e.getStatusCode());
        }
        // Dernier recours : création simple (laisse la distance par défaut)
        return postCreateCollection(name, withStamp(Map.of("name", name, "get_or_create", true), stamp));
    }

    /** Métadonnée d'estampille {@code spectra:embedding-model → modèle actif} (vide si inconnu). */
    private Map<String, Object> embeddingStamp() {
        String active = modelRegistry != null ? modelRegistry.getActiveEmbeddingModel() : null;
        return active != null && !active.isBlank()
                ? Map.of(EMBEDDING_MODEL_METADATA_KEY, active)
                : Map.of();
    }

    /** Ajoute l'estampille au corps de création (fusionnée avec d'éventuelles métadonnées). */
    private Map<String, Object> withStamp(Map<String, Object> body, Map<String, Object> stamp) {
        if (stamp.isEmpty()) return body;
        Map<String, Object> merged = new java.util.LinkedHashMap<>(body);
        merged.put("metadata", stamp);
        return merged;
    }

    @SuppressWarnings("unchecked")
    private CollectionRef postCreateCollection(String name, Map<String, Object> body) {
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
        return new CollectionRef(id, extractEmbeddingStamp(response));
    }

    /** Extrait l'estampille embedding des métadonnées d'une collection retournée par ChromaDB. */
    @SuppressWarnings("unchecked")
    private static String extractEmbeddingStamp(Map<String, Object> collection) {
        Object metadata = collection.get("metadata");
        if (metadata instanceof Map<?, ?> meta) {
            Object stamped = meta.get(EMBEDDING_MODEL_METADATA_KEY);
            return stamped != null ? stamped.toString() : null;
        }
        return null;
    }

    /**
     * Résout l'id d'une collection <b>sans</b> contrôle de cohérence embedding.
     * Réservé à la ré-indexation : c'est précisément la collection en MISMATCH
     * (bloquée partout ailleurs) qu'il faut pouvoir lire pour la reconstruire.
     */
    public String resolveCollectionIdUnchecked(String name) {
        validateCollectionName(name);
        CollectionRef ref = collectionCache.get(name);
        if (ref == null) {
            ref = createCollectionWithCosine(name);
            collectionCache.put(name, ref);
        }
        return ref.id();
    }

    /**
     * Remplace les embeddings de chunks existants (mêmes ids, nouveaux vecteurs).
     * Utilisé par la ré-indexation : documents et métadonnées restent inchangés.
     */
    public void updateEmbeddings(String collectionId, List<String> ids, List<List<Float>> embeddings) {
        if (ids.isEmpty()) {
            return;
        }
        Map<String, Object> body = Map.of("ids", ids, "embeddings", embeddings);
        webClient.post()
                .uri(COLLECTIONS_BASE + "/{id}/update", collectionId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class)
                .block(TIMEOUT_ADD);
    }

    /**
     * Ré-estampille une collection avec un modèle d'embedding (fin de ré-indexation).
     * Les métadonnées existantes (réglages {@code hnsw:*} des collections legacy) sont
     * préservées : seule l'estampille change. Le cache local est mis à jour pour que
     * l'accès suivant reflète immédiatement la nouvelle estampille.
     */
    @SuppressWarnings("unchecked")
    public void updateEmbeddingStamp(String collectionName, String collectionId, String embeddingModel) {
        Map<String, Object> current = webClient.get()
                .uri(COLLECTIONS_BASE + "/{id}", collectionId)
                .retrieve()
                .bodyToMono(Map.class)
                .block(TIMEOUT_DEFAULT);

        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        if (current != null && current.get("metadata") instanceof Map<?, ?> existing) {
            existing.forEach((k, v) -> metadata.put(String.valueOf(k), v));
        }
        metadata.put(EMBEDDING_MODEL_METADATA_KEY, embeddingModel);

        webClient.put()
                .uri(COLLECTIONS_BASE + "/{id}", collectionId)
                .bodyValue(Map.of("new_metadata", metadata))
                .retrieve()
                .bodyToMono(Void.class)
                .block(TIMEOUT_DEFAULT);

        collectionCache.put(collectionName, new CollectionRef(collectionId, embeddingModel));
        log.info("Collection '{}' ré-estampillée avec le modèle d'embedding '{}'", collectionName, embeddingModel);
    }

    /**
     * Liste les collections existantes (id, nom, métadonnées) — utilisé par le contrôle
     * de cohérence embedding au démarrage.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listCollections() {
        List<Map<String, Object>> collections = webClient.get()
                .uri(COLLECTIONS_BASE)
                .retrieve()
                .bodyToMono(List.class)
                .block(TIMEOUT_DEFAULT);
        return collections != null ? collections : List.of();
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
    @CircuitBreaker(name = "chroma", fallbackMethod = "queryFallback")
    @Retry(name = "chroma")
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

    /** Taille de page pour les récupérations complètes (borne chaque réponse HTTP). */
    private static final int BULK_PAGE_SIZE = 1000;

    /**
     * Récupère tous les documents d'une collection (sans embeddings).
     *
     * <p>Implémenté par pagination ({@link #getDocumentsPaged}) plutôt qu'un unique appel
     * {@code limit=1000000} : sur une grosse collection, la réponse monolithique explosait
     * le timeout (30 s) et le pic mémoire côté ChromaDB comme côté client. Le résultat agrégé
     * conserve la même forme ({@code ids}, {@code documents}, {@code metadatas}).</p>
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAllDocuments(String collectionId) {
        List<Object> ids       = new java.util.ArrayList<>();
        List<Object> documents = new java.util.ArrayList<>();
        List<Object> metadatas = new java.util.ArrayList<>();

        int offset = 0;
        while (true) {
            Map<String, Object> page = getDocumentsPaged(collectionId, BULK_PAGE_SIZE, offset);
            List<Object> pageIds = page != null ? (List<Object>) page.get("ids") : null;
            if (pageIds == null || pageIds.isEmpty()) break;

            ids.addAll(pageIds);
            if (page.get("documents") instanceof List<?> pageDocs) documents.addAll(pageDocs);
            if (page.get("metadatas") instanceof List<?> pageMetas) metadatas.addAll(pageMetas);

            if (pageIds.size() < BULK_PAGE_SIZE) break;
            offset += BULK_PAGE_SIZE;
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("ids", ids);
        result.put("documents", documents);
        result.put("metadatas", metadatas);
        return result;
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
    public int deleteBySource(String collectionId, String sourceFile) {
        return deleteByMetadata(collectionId, "sourceFile", sourceFile);
    }

    /**
     * Supprime tous les chunks dont la métadonnée {@code field} vaut {@code value}.
     * Utilisé avec {@code sha256} pour supprimer/remplacer un document par son identité de
     * contenu (les collisions de noms de fichiers homonymes ne suppriment plus les chunks
     * d'un autre document), et avec {@code sourceFile} en repli pour les chunks historiques
     * indexés avant l'ajout de la métadonnée {@code sha256}.
     */
    @SuppressWarnings("unchecked")
    public int deleteByMetadata(String collectionId, String field, String value) {
        // P2 — filtre where : seuls les IDs concernés sont récupérés
        Map<String, Object> body = Map.of(
                "where",   Map.of(field, Map.of("$eq", value)),
                "limit",   1000000,
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

        // Suppression par filtre where (un seul appel, payload constant) : une liste d'ids
        // pouvant atteindre le million d'entrées explosait le timeout court (10 s).
        webClient.post()
                .uri(COLLECTIONS_BASE + "/{id}/delete", collectionId)
                .bodyValue(Map.of("where", Map.of(field, Map.of("$eq", value))))
                .retrieve()
                .bodyToMono(Void.class)
                .block(TIMEOUT_BULK_GET);

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

    // ── Circuit breaker fallbacks ──────────────────────────────────────────────

    String getOrCreateCollectionFallback(String name, Throwable cause) {
        // Une incohérence de modèle d'embedding n'est PAS une indisponibilité : on la propage
        // telle quelle (elle est aussi ignorée par le circuit breaker via ignore-exceptions).
        if (cause instanceof EmbeddingModelMismatchException mismatch) {
            throw mismatch;
        }
        log.warn("[circuit-breaker] chroma ouvert — getOrCreateCollection('{}') : {}", name, cause.getMessage());
        throw new ChromaDbUnavailableException("ChromaDB temporairement indisponible", cause);
    }

    Map<String, Object> queryFallback(String collectionId, List<Float> queryEmbedding, int nResults, Throwable cause) {
        log.warn("[circuit-breaker] chroma ouvert — query sur '{}' : {}", collectionId, cause.getMessage());
        throw new ChromaDbUnavailableException("ChromaDB temporairement indisponible", cause);
    }

    public static class ChromaDbUnavailableException extends RuntimeException {
        public ChromaDbUnavailableException(String msg, Throwable cause) { super(msg, cause); }
    }

    /**
     * Le modèle d'embedding actif ne correspond pas à celui qui a indexé la collection.
     * Une réindexation (ou la réactivation du modèle d'origine) est requise.
     */
    public static class EmbeddingModelMismatchException extends RuntimeException {
        public EmbeddingModelMismatchException(String msg) { super(msg); }
    }

    /** Référence de collection résolue : id ChromaDB + estampille embedding (nullable). */
    private record CollectionRef(String id, String embeddingModel) {}

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
