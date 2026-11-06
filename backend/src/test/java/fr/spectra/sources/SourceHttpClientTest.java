package fr.spectra.sources;

import org.junit.jupiter.api.Test;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.net.UnknownHostException;
import static org.assertj.core.api.Assertions.*;

class SourceHttpClientTest {
    @Test void privateAndMetadataAddressesAreBlockedWithoutAdministratorAllowlist() {
        var client = new SourceHttpClient("");
        for (String address : new String[] {"127.0.0.1", "10.12.0.1", "169.254.169.254", "100.64.0.1"}) {
            assertThatThrownBy(() -> client.validate("http://" + address + "/docs"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("explicitly allowed");
        }
        assertThatThrownBy(() -> client.resolveAllowed("127.0.0.1"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test void explicitHostAllowlistTrimsWhitespaceAndAllowsOnlyNamedPrivateHost() throws Exception {
        var client = new SourceHttpClient(" 127.0.0.1 , 10.0.0.2 ");
        assertThat(client.validate("http://127.0.0.1:9000/").getHost()).isEqualTo("127.0.0.1");
        assertThat(client.resolveAllowed("127.0.0.1")).hasSize(1);
        assertThatThrownBy(() -> client.validate("http://127.0.0.2/")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void embeddedCredentialsFragmentsAndUnsupportedSchemesAreRejectedEvenForAllowedHost() {
        var client = new SourceHttpClient("127.0.0.1");
        for (String url : new String[] {"http://user:secret@127.0.0.1/", "http://127.0.0.1/#fragment", "file:///etc/passwd", "ftp://127.0.0.1/"}) {
            assertThatThrownBy(() -> client.validate(url)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void realGetPreservesUtf8ContentTypeAndIdentifiesCrawler() throws Exception {
        try (var server = server()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("<p>Résumé français</p>"));
            var response = new SourceHttpClient("127.0.0.1").get(url(server, "/docs"), 1024);
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.contentType()).contains("text/html", "utf-8");
            assertThat(new String(response.bytes(), StandardCharsets.UTF_8)).isEqualTo("<p>Résumé français</p>");
            var request = server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getPath()).isEqualTo("/docs");
            assertThat(request.getHeader("User-Agent")).isEqualTo("SpectraBot/1.0");
        }
    }

    @Test void redirectsAreReturnedWithoutRequestingTheirTarget() throws Exception {
        try (var server = server()) {
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", url(server, "/private")));
            var response = new SourceHttpClient("127.0.0.1").get(url(server, "/docs"), 1024);
            assertThat(response.status()).isEqualTo(302);
            assertThat(response.bytes()).isEmpty();
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @Test void absenceAndUpstreamFailureStayDistinctHttpResponses() throws Exception {
        try (var server = server()) {
            var client = new SourceHttpClient("127.0.0.1");
            server.enqueue(new MockResponse().setResponseCode(404).setHeader("Content-Type", "text/plain").setBody("gone"));
            server.enqueue(new MockResponse().setResponseCode(503).setBody("temporarily unavailable"));
            var missing = client.get(url(server, "/gone"), 1024);
            var unavailable = client.get(url(server, "/unavailable"), 1024);
            assertThat(missing.status()).isEqualTo(404);
            assertThat(unavailable.status()).isEqualTo(503);
            assertThat(missing.bytes()).isEmpty();
            assertThat(unavailable.bytes()).isEmpty();
        }
    }

    @Test void declaredAndChunkedOversizedResponsesBothFailTheConfiguredLimit() throws Exception {
        try (var server = server()) {
            var client = new SourceHttpClient("127.0.0.1");
            server.enqueue(new MockResponse().setBody("12345"));
            assertThatThrownBy(() -> client.get(url(server, "/declared"), 4))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size limit");
            server.enqueue(new MockResponse().setChunkedBody("12345", 2));
            assertThatThrownBy(() -> client.get(url(server, "/chunked"), 4))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size limit");
        }
    }

    @Test void emptySuccessfulResponseRemainsA200ForConnectorLevelValidation() throws Exception {
        try (var server = server()) {
            server.enqueue(new MockResponse().setResponseCode(200));
            var response = new SourceHttpClient("127.0.0.1").get(url(server, "/empty"), 1024);
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.bytes()).isEmpty();
        }
    }

    private static MockWebServer server() throws Exception {
        var server = new MockWebServer();
        server.start(InetAddress.getByName("127.0.0.1"), 0);
        return server;
    }
    private static String url(MockWebServer server, String path) {
        return "http://127.0.0.1:" + server.getPort() + path;
    }
}
