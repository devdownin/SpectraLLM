package fr.spectra.mcp;

import fr.spectra.service.ChromaDbClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.net.ConnectException;
import java.net.URI;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpToolFailureTest {

    static WebClientRequestException refused(String uri) {
        return new WebClientRequestException(
                new ConnectException("finishConnect(..) failed with error(-111): Connection refused: /127.0.0.1:1"),
                HttpMethod.GET, URI.create(uri), new HttpHeaders());
    }

    @Test
    void aRefusedConnectionNamesTheServiceAndSaysNothingWasRead() {
        assertThatThrownBy(() -> McpToolFailure.guard("t", () -> { throw refused("http://chromadb:8000/api/v2/collections"); }))
                .isInstanceOf(McpToolFailure.class)
                .hasMessageStartingWith("The service at http://chromadb:8000 did not answer (connection refused).")
                .hasMessageContaining("spectra_status")
                .hasMessageNotContaining("finishConnect")
                // Spring AI ajoute le message de la cause racine : il doit être vide, ni le texte
                // brut remplacé, ni la phrase une seconde fois.
                .satisfies(e -> assertThat(e.getCause().getMessage()).isEmpty());
    }

    @Test
    void anOpenCircuitBreakerIsNamedAsTheVectorStore() {
        assertThat(McpToolFailure.describe(new ChromaDbClient.ChromaDbUnavailableException("x", new RuntimeException())))
                .startsWith("The vector store (ChromaDB) is unavailable.");
    }

    @Test
    void aWrappedFailureIsFoundDownTheCauseChain() {
        assertThat(McpToolFailure.describe(new RuntimeException("wrapper", refused("http://llm-embed:8082/embedding"))))
                .contains("http://llm-embed:8082");
    }

    @Test
    void aBlockingTimeoutIsNamedAsOne() {
        assertThat(McpToolFailure.describe(new IllegalStateException("Timeout on blocking read for 30000000000 NANOSECONDS")))
                .startsWith("A service Spectra depends on did not answer in time.");
    }

    @Test
    void anUnknownDocumentIsNotFoundRatherThanAnError() {
        assertThat(McpToolFailure.describe(new NoSuchElementException("Document introuvable : abc")))
                .isEqualTo("Not found: Document introuvable : abc.");
    }

    @Test
    void aCallerMistakeAndAnUnknownFailurePassThroughUntouched() {
        IllegalArgumentException mistake = new IllegalArgumentException("question must not be blank");
        assertThatThrownBy(() -> McpToolFailure.guard("t", () -> { throw mistake; })).isSameAs(mistake);

        UnsupportedOperationException other = new UnsupportedOperationException("x");
        assertThatThrownBy(() -> McpToolFailure.guard("t", () -> { throw other; })).isSameAs(other);
    }
}
