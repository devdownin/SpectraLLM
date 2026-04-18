package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de UrlFetcherService.
 *
 * <p>Le WebClient est entièrement mocké — aucune connexion réseau réelle.
 * Les tests vérifient :
 * <ul>
 *   <li>La dérivation de nom de fichier depuis l'URL</li>
 *   <li>La détection du content-type via HEAD</li>
 *   <li>Le chemin PDF / TXT → téléchargement direct</li>
 *   <li>Le chemin HTML → browserless (avec et sans fallback)</li>
 *   <li>Les cas limites (HEAD échoue, body null, URL malformée)</li>
 * </ul>
 */
@SuppressWarnings("unchecked")
class UrlFetcherServiceTest {

    // Mocks de la chaîne fluide WebClient
    private WebClient mockWebClient;
    private WebClient.RequestHeadersUriSpec<?> headUriSpec;
    private WebClient.RequestHeadersSpec<?> headReqSpec;
    private WebClient.ResponseSpec headResponseSpec;

    private WebClient.RequestHeadersUriSpec<?> getUriSpec;
    private WebClient.RequestHeadersSpec<?> getReqSpec;
    private WebClient.ResponseSpec getResponseSpec;

    private UrlFetcherService fetcher;

    @BeforeEach
    void setUp() {
        mockWebClient    = mock(WebClient.class);
        headUriSpec      = mock(WebClient.RequestHeadersUriSpec.class);
        headReqSpec      = mock(WebClient.RequestHeadersSpec.class);
        headResponseSpec = mock(WebClient.ResponseSpec.class);
        getUriSpec       = mock(WebClient.RequestHeadersUriSpec.class);
        getReqSpec       = mock(WebClient.RequestHeadersSpec.class);
        getResponseSpec  = mock(WebClient.ResponseSpec.class);

        // Branchement de la chaîne HEAD
        when(mockWebClient.head()).thenReturn((WebClient.RequestHeadersUriSpec) headUriSpec);
        when(headUriSpec.uri(any(java.net.URI.class))).thenReturn((WebClient.RequestHeadersSpec) headReqSpec);
        when(headReqSpec.retrieve()).thenReturn(headResponseSpec);

        // Branchement de la chaîne GET
        when(mockWebClient.get()).thenReturn((WebClient.RequestHeadersUriSpec) getUriSpec);
        when(getUriSpec.uri(any(java.net.URI.class))).thenReturn((WebClient.RequestHeadersSpec) getReqSpec);
        when(getReqSpec.retrieve()).thenReturn(getResponseSpec);

        // Builder → retourne notre mockWebClient
        WebClient.Builder builder = mock(WebClient.Builder.class);
        when(builder.codecs(any(Consumer.class))).thenReturn(builder);
        when(builder.build()).thenReturn(mockWebClient);

        SpectraProperties.IngestionProperties ingestion =
                new SpectraProperties.IngestionProperties("http://browserless-test:3000");
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.ingestion()).thenReturn(ingestion);

        fetcher = new UrlFetcherService(builder, props);
    }

    // ── Dérivation du nom de fichier ──────────────────────────────────────────

    @Test
    void fetch_urlWithFilename_usesFilenamePart() {
        stubHeadPdf();
        stubGetBytes("PDF content".getBytes());

        UrlFetcherService.FetchedContent result = fetcher.fetch("https://example.com/docs/report.pdf");

        assertThat(result.filename()).isEqualTo("report.pdf");
    }

    @Test
    void fetch_urlWithoutFilename_usesHostPlusPdfExtension() {
        stubHeadPdf();
        stubGetBytes("PDF content".getBytes());

        UrlFetcherService.FetchedContent result = fetcher.fetch("https://example.com/");

        assertThat(result.filename()).isEqualTo("example_com.pdf");
    }

    @Test
    void fetch_urlPathWithNoExtension_usesHostPlusExtension() {
        stubHeadPdf();
        stubGetBytes("PDF bytes".getBytes());

        UrlFetcherService.FetchedContent result = fetcher.fetch("https://example.com/documents");

        // Pas d'extension dans le dernier segment → host + .pdf
        assertThat(result.filename()).isEqualTo("example_com.pdf");
    }

    // ── Détection content-type et routage ─────────────────────────────────────

    @Test
    void fetch_pdfContentType_usesDirectDownload() throws IOException {
        stubHeadPdf();
        byte[] pdfBytes = "%PDF-1.4 fake content".getBytes();
        stubGetBytes(pdfBytes);

        UrlFetcherService.FetchedContent result = fetcher.fetch("https://example.com/doc.pdf");

        assertThat(result.filename()).endsWith(".pdf");
        assertThat(result.inputStream().readAllBytes()).isEqualTo(pdfBytes);
    }

    @Test
    void fetch_txtContentType_usesDirectDownload() throws IOException {
        stubHeadWithContentType("text/plain");
        byte[] txtBytes = "Plain text content".getBytes();
        stubGetBytes(txtBytes);

        UrlFetcherService.FetchedContent result = fetcher.fetch("https://example.com/readme.txt");

        assertThat(result.filename()).endsWith(".txt");
        assertThat(result.inputStream().readAllBytes()).isEqualTo(txtBytes);
    }

    @Test
    void fetch_htmlContentType_callsBrowserless() throws IOException {
        stubHeadWithContentType("text/html");
        byte[] htmlBytes = "<html><body>Hello</body></html>".getBytes();
        stubBrowserlessGet(htmlBytes);

        UrlFetcherService.FetchedContent result = fetcher.fetch("https://example.com/page");

        assertThat(result.filename()).endsWith(".html");
        assertThat(result.inputStream().readAllBytes()).isEqualTo(htmlBytes);
    }

    @Test
    void fetch_headRequestFails_defaultsToHtmlViaBrowserless() throws IOException {
        // HEAD échoue → on suppose HTML
        when(headResponseSpec.toBodilessEntity())
                .thenReturn(Mono.error(new RuntimeException("Connection refused")));
        byte[] htmlBytes = "<html>fallback</html>".getBytes();
        stubBrowserlessGet(htmlBytes);

        UrlFetcherService.FetchedContent result = fetcher.fetch("https://example.com/unknown");

        // Doit quand même retourner quelque chose (browserless ou direct)
        assertThat(result).isNotNull();
        assertThat(result.inputStream().readAllBytes()).isNotEmpty();
    }

    @Test
    void fetch_browserlessFails_fallsBackToDirectFetch() throws IOException {
        stubHeadWithContentType("text/html");
        // Browserless renvoie une erreur
        when(getUriSpec.uri(any(java.net.URI.class))).thenAnswer(inv -> {
            java.net.URI uri = inv.getArgument(0);
            if (uri.toString().contains("browserless")) {
                // Premier appel (browserless) → erreur
                when(getReqSpec.retrieve()).thenReturn(getResponseSpec);
                when(getResponseSpec.bodyToMono(byte[].class))
                        .thenReturn(Mono.error(new RuntimeException("browserless down")));
            } else {
                // Deuxième appel (direct) → succès
                when(getReqSpec.retrieve()).thenReturn(getResponseSpec);
                when(getResponseSpec.bodyToMono(byte[].class))
                        .thenReturn(Mono.just("<html>direct</html>".getBytes()));
            }
            return getReqSpec;
        });

        UrlFetcherService.FetchedContent result = fetcher.fetch("https://example.com/page");

        assertThat(result).isNotNull();
        assertThat(result.filename()).isNotBlank();
    }

    // ── Contenu null / body vide ──────────────────────────────────────────────

    @Test
    void fetch_nullBody_returnsEmptyInputStream() throws IOException {
        stubHeadPdf();
        when(getResponseSpec.bodyToMono(byte[].class)).thenReturn(Mono.justOrEmpty(null));

        UrlFetcherService.FetchedContent result = fetcher.fetch("https://example.com/empty.pdf");

        assertThat(result).isNotNull();
        assertThat(result.inputStream().readAllBytes()).isEmpty();
    }

    // ── FetchedContent ────────────────────────────────────────────────────────

    @Test
    void fetchedContent_filenameAndStream_accessible() throws IOException {
        stubHeadPdf();
        stubGetBytes("data".getBytes());

        UrlFetcherService.FetchedContent content = fetcher.fetch("https://example.com/a.pdf");

        assertThat(content.filename()).isNotBlank();
        InputStream is = content.inputStream();
        assertThat(is).isNotNull();
        assertThat(is.readAllBytes()).isNotEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubHeadPdf() {
        stubHeadWithContentType("application/pdf");
    }

    private void stubHeadWithContentType(String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", contentType);
        ResponseEntity<Void> response = ResponseEntity.ok().headers(headers).build();
        when(headResponseSpec.toBodilessEntity()).thenReturn(Mono.just(response));
    }

    private void stubGetBytes(byte[] bytes) {
        when(getResponseSpec.bodyToMono(byte[].class)).thenReturn(Mono.just(bytes));
    }

    private void stubBrowserlessGet(byte[] bytes) {
        // browserless est appelé via GET sur ${browserlessUrl}/content?url=...
        // L'URI contiendra "browserless" dans notre config de test
        when(getUriSpec.uri(any(java.net.URI.class))).thenReturn((WebClient.RequestHeadersSpec) getReqSpec);
        when(getReqSpec.retrieve()).thenReturn(getResponseSpec);
        when(getResponseSpec.bodyToMono(byte[].class)).thenReturn(Mono.just(bytes));
    }
}
