package fr.spectra.config;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les clients LLM doivent pouvoir consommer une réponse plus grosse que les 256 Ko du plafond
 * Spring par défaut.
 *
 * <p>Le client d'embedding n'en fixait aucun. Comme
 * {@code spectra.pipeline.embedding-batch-size} vaut 32 et qu'un lot de 32 vecteurs de 768
 * dimensions sérialisés en JSON dépasse 256 Ko, l'ingestion échouait sur
 * {@code DataBufferLimitException: Exceeded limit on max bytes to buffer : 262144}, ouvrait le
 * circuit breaker et n'indexait AUCUN chunk. Autrement dit : le défaut applicatif ne pouvait
 * pas fonctionner, et seules les valeurs plus basses imposées par Compose et {@code .env}
 * masquaient le défaut au lieu de le révéler.
 *
 * <p>Le test envoie 1 Mo — au-delà de l'ancien plafond, très en deçà du nouveau (16 Mo) — à
 * chacun des quatre clients concernés. Sans {@code maxInMemorySize}, chaque cas lève.
 */
class AppConfigBufferLimitTest {

    /** Au-delà des 256 Ko du défaut Spring, bien en deçà des 16 Mo configurés. */
    private static final int PAYLOAD_BYTES = 1_048_576;

    private MockWebServer server;
    private AppConfig appConfig;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        appConfig = new AppConfig();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private String baseUrl() {
        return server.url("/").toString();
    }

    /** Réponse JSON valide dont le corps dépasse largement l'ancien plafond. */
    private void enqueueLargeJson() {
        String filler = "x".repeat(PAYLOAD_BYTES);
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"data\":\"" + filler + "\"}"));
    }

    /**
     * Propriétés minimales pointant chaque endpoint vers MockWebServer. {@code runtime} reste
     * nul : le client de chat basculerait sinon sur un port local au lieu de l'URL fournie.
     */
    private SpectraProperties propsPointingAtMock() {
        SpectraProperties.EndpointProperties endpoint =
                new SpectraProperties.EndpointProperties(baseUrl(), null, null, null);
        SpectraProperties.LlmProperties llm = new SpectraProperties.LlmProperties(
                baseUrl(), null, null, null, null, endpoint, endpoint, null);
        SpectraProperties props = org.mockito.Mockito.mock(SpectraProperties.class);
        org.mockito.Mockito.when(props.llm()).thenReturn(llm);
        org.mockito.Mockito.when(props.reranker()).thenReturn(
                new SpectraProperties.RerankerProperties(true, baseUrl(), null, null, 20, "http", null));
        return props;
    }

    /** Consomme une réponse de 1 Mo ; lève DataBufferLimitException si le plafond est à 256 Ko. */
    private void assertConsumesLargeResponse(Function<SpectraProperties, WebClient> factory,
                                             String clientName) {
        enqueueLargeJson();
        WebClient client = factory.apply(propsPointingAtMock());

        Map<?, ?> body = client.get()
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(30));

        assertThat(body)
                .as("%s doit consommer une réponse de 1 Mo sans buter sur le plafond de 256 Ko",
                        clientName)
                .isNotNull();
        assertThat((String) body.get("data")).hasSize(PAYLOAD_BYTES);
    }

    @Test
    @DisplayName("client d'embedding : 1 Mo consommé — le cas qui cassait l'ingestion")
    void embeddingConsommeUneGrosseReponse() {
        assertConsumesLargeResponse(appConfig::llamaCppEmbeddingWebClient, "llamaCppEmbeddingWebClient");
    }

    @Test
    @DisplayName("client de chat : 1 Mo consommé")
    void chatConsommeUneGrosseReponse() {
        assertConsumesLargeResponse(appConfig::llamaCppChatWebClient, "llamaCppChatWebClient");
    }

    @Test
    @DisplayName("client LLM générique : 1 Mo consommé")
    void llmGeneriqueConsommeUneGrosseReponse() {
        assertConsumesLargeResponse(appConfig::llmWebClient, "llmWebClient");
    }

    @Test
    @DisplayName("client de reranking : 1 Mo consommé")
    void rerankerConsommeUneGrosseReponse() {
        assertConsumesLargeResponse(appConfig::rerankerWebClient, "rerankerWebClient");
    }
}
