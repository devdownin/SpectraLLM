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

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Comptage par le tokenizer du GGUF servi : {@code POST /tokenize}, tokens spéciaux compris. */
class LlamaCppEmbeddingClientTokenizeTest {

    private MockWebServer server;
    private LlamaCppEmbeddingClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new LlamaCppEmbeddingClient(WebClient.builder().baseUrl(server.url("/").toString()).build(),
                mock(SpectraProperties.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void countsTokensWithTheServedTokenizerIncludingSpecialTokens() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"tokens\":[101,2054,102]}"));
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"tokens\":[101,102]}"));

        assertThat(client.countTokens(List.of("search_query: quoi", ""))).contains(List.of(3, 2));

        RecordedRequest first = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(first.getPath()).isEqualTo("/tokenize");
        JsonNode body = new ObjectMapper().readTree(first.getBody().readUtf8());
        assertThat(body.path("content").asText()).isEqualTo("search_query: quoi");
        assertThat(body.path("add_special").asBoolean()).isTrue();
    }
}
