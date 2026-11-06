package fr.spectra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Function calling natif de llama.cpp : requête {@code tools} / {@code tool_choice} et lecture des {@code tool_calls}. */
class LlamaCppChatClientToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<LlmChatClient.ToolDefinition> TOOLS = List.of(new LlmChatClient.ToolDefinition(
            "lookup", "Retrouve un identifiant exact.",
            Map.of("type", "object", "properties", Map.of("identifier", Map.of("type", "string")))));

    private MockWebServer server;
    private LlamaCppChatClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.llm()).thenReturn(SpectraProperties.LlmProperties.defaults());
        when(props.pipeline()).thenReturn(new SpectraProperties.PipelineProperties(null, null, null, null, null, null));
        client = new LlamaCppChatClient(webClient, mock(ModelRegistryService.class),
                mock(LlamaCppRuntimeOrchestrator.class), MAPPER, props);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private void enqueue(Map<String, Object> message) throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(MAPPER.writeValueAsString(Map.of("choices", List.of(Map.of("message", message))))));
    }

    private JsonNode takeBody() throws Exception {
        return MAPPER.readTree(server.takeRequest().getBody().readUtf8());
    }

    @Test
    void chatTurn_declaresTheToolsAndLetsTheModelChoose() throws Exception {
        enqueue(Map.of("role", "assistant", "content", "Réponse [1]."));

        client.chatTurn(List.of(LlmChatClient.ToolMessage.system("S"), LlmChatClient.ToolMessage.user("Q")),
                TOOLS, 0.2f, 0.9f);

        JsonNode body = takeBody();
        assertThat(body.path("tool_choice").asText()).isEqualTo("auto");
        JsonNode fn = body.path("tools").get(0);
        assertThat(fn.path("type").asText()).isEqualTo("function");
        assertThat(fn.path("function").path("name").asText()).isEqualTo("lookup");
        assertThat(fn.path("function").path("parameters").path("properties").has("identifier")).isTrue();
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.2, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void chatTurn_plainContentIsAFinalAnswerWithoutToolCalls() throws Exception {
        enqueue(Map.of("role", "assistant", "content", "La valeur est 512 [1]."));

        LlmChatClient.ToolTurn turn = client.chatTurn(List.of(LlmChatClient.ToolMessage.user("Q")), TOOLS, 0.7f, 0.9f);

        assertThat(turn.hasToolCalls()).isFalse();
        assertThat(turn.content()).isEqualTo("La valeur est 512 [1].");
    }

    @Test
    void chatTurn_readsToolCallsWithTheirJsonArguments() throws Exception {
        enqueue(Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of(
                "id", "call_1", "type", "function",
                "function", Map.of("name", "lookup", "arguments", "{\"identifier\":\"ENG-39100\"}")))));

        LlmChatClient.ToolTurn turn = client.chatTurn(List.of(LlmChatClient.ToolMessage.user("Q")), TOOLS, 0.7f, 0.9f);

        assertThat(turn.hasToolCalls()).isTrue();
        assertThat(turn.toolCalls().getFirst().name()).isEqualTo("lookup");
        assertThat(turn.toolCalls().getFirst().id()).isEqualTo("call_1");
        assertThat(MAPPER.readTree(turn.toolCalls().getFirst().argumentsJson()).path("identifier").asText())
                .isEqualTo("ENG-39100");
    }

    @Test
    void chatTurn_withoutToolsOmitsTheToolFields() throws Exception {
        enqueue(Map.of("role", "assistant", "content", "ok"));

        client.chatTurn(List.of(LlmChatClient.ToolMessage.user("Q")), List.of(), 0.7f, 0.9f);

        JsonNode body = takeBody();
        assertThat(body.has("tools")).isFalse();
        assertThat(body.has("tool_choice")).isFalse();
    }

    @Test
    void chatTurn_encodesAnAssistantToolCallAndItsToolResult() throws Exception {
        enqueue(Map.of("role", "assistant", "content", "ok"));
        var call = new LlmChatClient.ToolCall("call_1", "lookup", "{\"identifier\":\"X\"}");

        client.chatTurn(List.of(
                LlmChatClient.ToolMessage.user("Q"),
                new LlmChatClient.ToolMessage("assistant", "", List.of(call), null),
                new LlmChatClient.ToolMessage("tool", "résultat", List.of(), "call_1")), TOOLS, 0.7f, 0.9f);

        JsonNode messages = takeBody().path("messages");
        assertThat(messages.get(1).path("tool_calls").get(0).path("function").path("name").asText()).isEqualTo("lookup");
        assertThat(messages.get(2).path("tool_call_id").asText()).isEqualTo("call_1");
    }

    @Test
    void defaultProviderRefusesToolCalling() {
        LlmChatClient plain = mock(LlmChatClient.class, org.mockito.Mockito.CALLS_REAL_METHODS);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> plain.chatTurn(List.of(), List.of(), 0.7f, 0.9f))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
