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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Décodage contraint : {@code chatJson} doit demander au serveur llama.cpp de restreindre
 * la génération à un objet JSON valide, alors que {@code chat} laisse le modèle libre.
 *
 * <p>C'est la garantie structurelle qui remplace le rattrapage au parsing : sans le champ
 * {@code response_format}, un modèle « reasoning » peut préfixer sa réponse d'un bloc de
 * réflexion et faire échouer tout appelant qui attend du JSON.</p>
 */
class LlamaCppChatClientJsonModeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockWebServer server;
    private LlamaCppChatClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();

        SpectraProperties props = mock(SpectraProperties.class);
        when(props.llm()).thenReturn(SpectraProperties.LlmProperties.defaults());
        // Le client lit le timeout de génération au moment de sa construction.
        when(props.pipeline()).thenReturn(
                new SpectraProperties.PipelineProperties(null, null, null, null, null, null));

        client = new LlamaCppChatClient(webClient, mock(ModelRegistryService.class),
                mock(LlamaCppRuntimeOrchestrator.class), MAPPER, props);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    /** Réponse minimale au format OpenAI attendu par le client. */
    private void enqueueContent(String content) throws Exception {
        String body = MAPPER.writeValueAsString(java.util.Map.of(
                "choices", java.util.List.of(java.util.Map.of(
                        "message", java.util.Map.of("role", "assistant", "content", content)))));
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body));
    }

    private JsonNode lastRequestBody() throws Exception {
        RecordedRequest request = server.takeRequest();
        return MAPPER.readTree(request.getBody().readUtf8());
    }

    @Test
    void chatJson_constrainsGenerationToAJsonObject() throws Exception {
        enqueueContent("{\"categories\":[]}");

        client.chatJson("système", "utilisateur", 0.1f, 0.9f);

        JsonNode body = lastRequestBody();
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
    }

    @Test
    void chat_leavesGenerationFree() throws Exception {
        enqueueContent("du texte libre");

        client.chat("système", "utilisateur", 0.7f, 0.9f);

        // Aucune contrainte : le chat conversationnel ne doit pas être bridé au JSON.
        assertThat(lastRequestBody().has("response_format")).isFalse();
    }

    @Test
    void chatJson_stillCarriesGenerationParameters() throws Exception {
        enqueueContent("{}");

        client.chatJson("système", "utilisateur", 0.1f, 0.5f);

        JsonNode body = lastRequestBody();
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.1);
        assertThat(body.path("top_p").asDouble()).isEqualTo(0.5);
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("messages")).hasSize(2);
    }

    @Test
    void chatJson_defaultOverload_usesStandardGenerationParameters() throws Exception {
        enqueueContent("{}");

        client.chatJson("système", "utilisateur");

        JsonNode body = lastRequestBody();
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.7);
        assertThat(body.path("top_p").asDouble()).isEqualTo(0.9);
    }

    @Test
    void chatJson_returnsTheContentUnchanged() throws Exception {
        enqueueContent("{\"summary\":\"ok\"}");

        assertThat(client.chatJson("s", "u", 0.1f, 0.9f)).isEqualTo("{\"summary\":\"ok\"}");
    }
}
