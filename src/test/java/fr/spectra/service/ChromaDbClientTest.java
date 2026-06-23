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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test anti-régression du client ChromaDB.
 *
 * Verrou : la création d'une collection DOIT demander l'espace de distance
 * {@code cosine}. Si quelqu'un retire/modifie ce réglage, les scores de
 * similarité changent silencieusement (ChromaDB applique L2 par défaut) et
 * tout le pipeline RAG est faussé — ce test échoue alors immédiatement.
 */
class ChromaDbClientTest {

    private MockWebServer server;
    private ChromaDbClient client;

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
        client = new ChromaDbClient(webClient, props);
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
}
