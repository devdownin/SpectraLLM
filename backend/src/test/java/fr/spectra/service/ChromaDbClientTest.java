package fr.spectra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests anti-régression du client ChromaDB.
 *
 * <p>Verrou 1 : la création d'une collection DOIT demander l'espace de distance
 * {@code cosine}. Si quelqu'un retire/modifie ce réglage, les scores de
 * similarité changent silencieusement (ChromaDB applique L2 par défaut) et
 * tout le pipeline RAG est faussé — ce test échoue alors immédiatement.</p>
 *
 * <p>Verrou 2 : chaque collection est estampillée avec le modèle d'embedding actif
 * ({@code spectra:embedding-model}) et un accès avec un modèle actif différent de
 * l'estampille DOIT être rejeté — sinon un changement de modèle d'embedding
 * corrompt silencieusement l'index (vecteurs non comparables).</p>
 */
class ChromaDbClientTest {

    private MockWebServer server;
    private ChromaDbClient client;
    private ModelRegistryService modelRegistry;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        WebClient webClient = WebClient.builder()
                .baseUrl(server.url("/").toString())
                .build();
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.chromadb()).thenReturn(
                new SpectraProperties.ChromaDbProperties(server.url("/").toString(), "col"));
        modelRegistry = mock(ModelRegistryService.class);
        when(modelRegistry.getActiveEmbeddingModel()).thenReturn("nomic-embed-text");
        client = new ChromaDbClient(webClient, props, modelRegistry);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void getOrCreateCollection_demandeEspaceCosinus() throws Exception {
        // ChromaDB 1.x renvoie l'id de la collection créée.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"col-123\"}"));

        String id = client.getOrCreateCollection("ma_collection");

        assertThat(id).isEqualTo("col-123");

        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath())
                .isEqualTo("/api/v2/tenants/default_tenant/databases/default_database/collections");

        JsonNode body = new ObjectMapper().readTree(request.getBody().readUtf8());
        // ── Verrou anti-régression : la distance DOIT être le cosinus ──────────
        assertThat(body.path("configuration").path("hnsw").path("space").asText())
                .as("configuration.hnsw.space")
                .isEqualTo("cosine");
        assertThat(body.path("name").asText()).isEqualTo("ma_collection");
        assertThat(body.path("get_or_create").asBoolean()).isTrue();
    }

    @Test
    void getOrCreateCollection_estampilleLeModeleEmbeddingActif() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"col-123\"}"));

        client.getOrCreateCollection("ma_collection");

        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        JsonNode body = new ObjectMapper().readTree(request.getBody().readUtf8());
        // ── Verrou anti-régression : l'estampille embedding DOIT être posée ────
        assertThat(body.path("metadata").path(ChromaDbClient.EMBEDDING_MODEL_METADATA_KEY).asText())
                .as("metadata['" + ChromaDbClient.EMBEDDING_MODEL_METADATA_KEY + "']")
                .isEqualTo("nomic-embed-text");
    }

    @Test
    void getOrCreateCollection_estampilleIdentique_accesAutorise() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"col-1\",\"metadata\":{\"spectra:embedding-model\":\"nomic-embed-text\"}}"));

        assertThat(client.getOrCreateCollection("coherente")).isEqualTo("col-1");
    }

    @Test
    void getOrCreateCollection_estampilleDifferente_rejette() {
        // Collection existante, indexée avec un AUTRE modèle que le modèle actif.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"col-2\",\"metadata\":{\"spectra:embedding-model\":\"ancien-modele\"}}"));

        assertThatThrownBy(() -> client.getOrCreateCollection("incoherente"))
                .isInstanceOf(ChromaDbClient.EmbeddingModelMismatchException.class)
                .hasMessageContaining("ancien-modele")
                .hasMessageContaining("nomic-embed-text");
    }

    @Test
    void getOrCreateCollection_cacheHit_revalideContreLeModeleActif() {
        // 1er accès : cohérent (estampille = modèle actif) → mis en cache.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"col-3\",\"metadata\":{\"spectra:embedding-model\":\"nomic-embed-text\"}}"));
        assertThat(client.getOrCreateCollection("cachee")).isEqualTo("col-3");

        // Le modèle actif change à chaud : l'accès suivant (servi par le cache) doit être rejeté.
        when(modelRegistry.getActiveEmbeddingModel()).thenReturn("nouveau-modele");
        assertThatThrownBy(() -> client.getOrCreateCollection("cachee"))
                .isInstanceOf(ChromaDbClient.EmbeddingModelMismatchException.class);
    }

    @Test
    void getOrCreateCollection_collectionLegacySansEstampille_accesAutorise() {
        // Collection créée avant l'estampillage : cohérence invérifiable → accès permis (warning).
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"col-4\",\"metadata\":{\"hnsw:space\":\"cosine\"}}"));

        assertThat(client.getOrCreateCollection("legacy")).isEqualTo("col-4");
    }
}
