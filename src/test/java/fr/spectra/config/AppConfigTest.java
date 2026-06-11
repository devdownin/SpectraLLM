package fr.spectra.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.spectra.service.ChromaDbClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppConfigTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void chromaDbWebClient_buffersLargePagedResponses() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String largeDocument = "x".repeat(300_000);
        byte[] response = """
                {"ids":["chunk-1"],"documents":["%s"],"metadatas":[{"sourceFile":"doc.txt"}]}
                """.formatted(largeDocument).getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> sendJson(exchange, response));
        server.start();

        SpectraProperties.ChromaDbProperties chromaProps =
                new SpectraProperties.ChromaDbProperties(baseUrl(), "spectra_documents", 1);
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.chromadb()).thenReturn(chromaProps);

        WebClient webClient = new AppConfig().chromaDbWebClient(props);
        ChromaDbClient chromaDbClient = new ChromaDbClient(webClient, props);

        Map<String, Object> result = chromaDbClient.getDocumentsPaged("collection-id", 500, 0);

        assertThat(result.get("ids")).asList().containsExactly("chunk-1");
        assertThat(result.get("documents")).asList().containsExactly(largeDocument);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void sendJson(HttpExchange exchange, byte[] response) throws IOException {
        exchange.getResponseHeaders().put("Content-Type", List.of("application/json"));
        exchange.sendResponseHeaders(200, response.length);
        try (var body = exchange.getResponseBody()) {
            body.write(response);
        } finally {
            exchange.close();
        }
    }
}
