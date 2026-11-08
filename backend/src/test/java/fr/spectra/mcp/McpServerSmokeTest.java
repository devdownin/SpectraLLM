package fr.spectra.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le serveur MCP répond sur un vrai port, comme un client tiers le verrait.
 *
 * <p>Lire les beans ne suffit pas : l'outil peut être enregistré et l'endpoint lié par personne
 * (transport SSE à la place de Streamable HTTP, route avalée par une autre). Ce test fait la
 * poignée de main puis {@code tools/list} sur {@code /mcp}, avec la clé API.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spectra.mcp.enabled=true", "SPECTRA_API_KEY=test-key",
                """
                SPECTRA_MCP_CALLERS_JSON={"alice":{"key":"alice-key","collections":["spectra_documents"]},"bob":{"key":"bob-key","collections":["private_bob"]}}
                """})
@ActiveProfiles("smoke")
class McpServerSmokeTest {

    private static final String ACCEPT = "application/json, text/event-stream";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HttpResponse<String> post(String body, String sessionId, String apiKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", ACCEPT)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (apiKey != null) request.header("X-API-Key", apiKey);
        if (sessionId != null) request.header("Mcp-Session-Id", sessionId);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theToolsAreListedOverTheStreamableEndpoint() throws Exception {
        HttpResponse<String> init = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                "capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}""", null, "alice-key");
        assertThat(init.statusCode()).isEqualTo(200);
        String session = init.headers().firstValue("Mcp-Session-Id").orElse(null);

        post("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}""", session, "alice-key");
        HttpResponse<String> list = post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}""", session, "alice-key");

        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("spectra_search", "spectra_ask", "spectra_list_documents",
                "spectra_preview_document", "spectra_read_document", "spectra_status");
    }

    @Test
    void aToolCallIsReadableInTheJournal() throws Exception {
        HttpResponse<String> init = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                "capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}""", null, "alice-key");
        String session = init.headers().firstValue("Mcp-Session-Id").orElse(null);
        post("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}""", session, "alice-key");
        post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"spectra_status","arguments":{}}}""",
                session, "alice-key");

        HttpResponse<String> journal = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/mcp/calls"))
                .header("X-API-Key", "test-key").GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(journal.statusCode()).isEqualTo(200);
        assertThat(journal.body()).contains("\"tool\":\"spectra_status\"", "\"outcome\":\"OK\"");
    }

    @Test
    void withoutTheKeyMcpIsRefused() throws Exception {
        assertThat(post("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}""", null, null).statusCode()).isEqualTo(401);
    }
    @Test
    void callerCannotUseAnotherCollectionOrSession() throws Exception {
        var init = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                "capabilities":{},"clientInfo":{"name":"scope","version":"1"}}}""", null, "alice-key");
        assertThat(init.statusCode()).isEqualTo(200);
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        post("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}""", session, "alice-key");
        var denied = post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                  "name":"spectra_list_documents","arguments":{"collection":"private_bob"}}}""",
                session, "alice-key");
        assertThat(denied.body()).contains("Collection is not accessible to this MCP caller");
        var deniedSearch = post("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
                  "name":"spectra_search","arguments":{"question":"private report","collection":"private_bob"}}}""",
                session, "alice-key");
        assertThat(deniedSearch.body()).contains("Collection is not accessible to this MCP caller");
        assertThat(post("""
                {"jsonrpc":"2.0","id":3,"method":"tools/list"}""", session, "bob-key").statusCode()).isEqualTo(403);
        var rest = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/mcp/calls"))
                .header("X-API-Key", "alice-key").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(rest.statusCode()).isEqualTo(401);
    }
}
