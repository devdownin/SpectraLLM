package fr.spectra.sources;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSourceConnectorTest {
    private final SourceHttpClient http = mock(SourceHttpClient.class);
    private final WebSourceConnector connector = new WebSourceConnector(http);
    private static final String ORIGIN = "https://docs.example.test";
    @BeforeEach void setup() throws Exception {
        when(http.validate(anyString())).thenAnswer(a -> URI.create(a.getArgument(0)));
        response("/robots.txt", 404, "text/plain", "");
    }

    @Test void encodedParentTraversalNeverEscapesConfiguredWebPath() {
        assertThat(WebSourceConnector.within(URI.create(ORIGIN + "/docs/%2e%2e/private"), URI.create(ORIGIN + "/docs"))).isFalse();
        assertThat(WebSourceConnector.within(URI.create(ORIGIN + "/docs/chapter"), URI.create(ORIGIN + "/docs"))).isTrue();
    }

    @Test void robotsNoindexAndOriginBoundaryControlWhatEntersTheCorpus() throws Exception {
        response("/robots.txt", 200, "text/plain", "User-agent: *\nDisallow: /docs/private\n");
        response("/docs", 200, "text/html", "<title>Docs</title><p>Guide</p><a href='/docs/public'>Public</a><a href='/docs/private'>Private</a><a href='https://other.example.test/docs'>External</a><a href='/docs-other'>Outside path</a>");
        response("/docs/public", 200, "text/html", "<meta name='robots' content='noindex'><p>Not indexed</p>");
        var sink = new LocalFolderConnectorTest.CollectingSink();
        assertThat(connector.scan(config(null, 100), sink)).isTrue();
        assertThat(sink.items).extracting(SourceConnector.Item::externalId).containsExactly(ORIGIN + "/docs");
        verify(http, never()).get(eq(ORIGIN + "/docs/private"), anyLong());
        verify(http, never()).get(eq(ORIGIN + "/docs-other"), anyLong());
        verify(http, never()).get(eq("https://other.example.test/docs"), anyLong());
    }

    @Test void sitemapDiscoversUnlinkedPageAndPreservesOriginalUrlAndTitle() throws Exception {
        response("/sitemap.xml", 200, "application/xml", "<urlset xmlns='http://www.sitemaps.org/schemas/sitemap/0.9'><url><loc>" + ORIGIN + "/docs/unlinked</loc></url></urlset>");
        response("/docs", 200, "text/html", "<title>Home</title><p>Home</p>");
        response("/docs/unlinked", 200, "text/html", "<title>Unlinked guide</title><p>Text</p>");
        var sink = new LocalFolderConnectorTest.CollectingSink();
        assertThat(connector.scan(config(ORIGIN + "/sitemap.xml", 100), sink)).isTrue();
        assertThat(sink.items).hasSize(2);
        SourceConnector.Item page = sink.items.stream().filter(i -> i.title().equals("Unlinked guide")).findFirst().orElseThrow();
        assertThat(page.sourceUrl()).isEqualTo(ORIGIN + "/docs/unlinked");
        assertThat(page.metadata()).containsEntry("sourceUrl", page.sourceUrl());
    }

    @Test void explicitGoneSignalDeletesOnlyRequestedPageAndNetworkFailureIsNotAbsence() throws Exception {
        response("/docs", 200, "text/html", "<p>Home</p><a href='/docs/gone'>Gone</a><a href='/docs/failing'>Fail</a>");
        response("/docs/gone", 410, "text/plain", "");
        response("/docs/failing", 503, "text/plain", "");
        var sink = new LocalFolderConnectorTest.CollectingSink();
        assertThatThrownBy(() -> connector.scan(config(null, 100), sink))
                .isInstanceOf(SourceSupport.SourceProblem.class).hasMessageContaining("503");
        assertThat(sink.deleted).containsExactly(ORIGIN + "/docs/gone");
        assertThat(sink.items).hasSize(1);
    }

    @Test void documentLimitReturnsIncompleteInsteadOfPretendingCrawlFinished() throws Exception {
        response("/docs", 200, "text/html", "<p>Home</p><a href='/docs/next'>Next</a>");
        var sink = new LocalFolderConnectorTest.CollectingSink();
        assertThat(connector.scan(config(null, 1), sink)).isFalse();
        assertThat(sink.items).hasSize(1);
        verify(http, never()).get(eq(ORIGIN + "/docs/next"), anyLong());
    }

    @Test void unavailableRobotsFileDefersCrawlRatherThanIgnoringPolicy() throws Exception {
        response("/robots.txt", 503, "text/plain", "");
        var sink = new LocalFolderConnectorTest.CollectingSink();
        assertThatThrownBy(() -> connector.scan(config(null, 100), sink))
                .isInstanceOf(SourceSupport.SourceProblem.class).hasMessageContaining("robots.txt");
        assertThat(sink.items).isEmpty();
        verify(http, never()).get(eq(ORIGIN + "/docs"), anyLong());
    }


    @Test void markdownExtensionFilterKeepsPlainTextMarkdownUrls() throws Exception {
        response("/docs/guide.md", 200, "text/plain; charset=utf-8", "# Guide\nUseful content");
        var config = new SourceModels.Config(null, ORIGIN + "/docs/guide.md", null, null, null, null,
                null, null, null, null, List.of("md"), List.of(), 100, 2, 1);
        var sink = new LocalFolderConnectorTest.CollectingSink();
        assertThat(connector.scan(config, sink)).isTrue();
        assertThat(sink.items).extracting(SourceConnector.Item::externalId).containsExactly(ORIGIN + "/docs/guide.md");
        assertThat(sink.items.getFirst().filename()).endsWith(".md");
    }

    @Test void restrictiveBotSpecificDirectiveWinsEvenWhenGenericRobotsTagAllowsIndexing() throws Exception {
        response("/docs", 200, "text/html", "<meta name='robots' content='index, follow'><meta name='SpectraBot' content='noindex'><p>Restricted page</p>");
        var sink = new LocalFolderConnectorTest.CollectingSink();
        assertThat(connector.scan(config(null, 100), sink)).isTrue();
        assertThat(sink.items).isEmpty();
    }

    @Test void oversizedSitemapStillIndexesQueuedStartUrlAndReportsIncompleteDiscovery() throws Exception {
        response("/sitemap.xml", 200, "application/xml", "<urlset><url><loc>" + ORIGIN + "/docs</loc></url><url><loc>" + ORIGIN + "/docs/next</loc></url></urlset>");
        response("/docs", 200, "text/html", "<title>Starting document</title><p>Useful content</p>");
        var sink = new LocalFolderConnectorTest.CollectingSink();
        assertThat(connector.scan(config(ORIGIN + "/sitemap.xml", 1), sink)).isFalse();
        assertThat(sink.items).extracting(SourceConnector.Item::externalId).containsExactly(ORIGIN + "/docs");
        verify(http, never()).get(eq(ORIGIN + "/docs/next"), anyLong());
    }

    private void response(String path, int status, String contentType, String body) throws Exception {
        when(http.get(eq(ORIGIN + path), anyLong())).thenReturn(new SourceHttpClient.Response(status, contentType, body.getBytes(StandardCharsets.UTF_8)));
    }
    private static SourceModels.Config config(String sitemap, int max) {
        return new SourceModels.Config(null, ORIGIN + "/docs", sitemap, null, null, null, null, null, null, null, List.of(), List.of(), max, 3, 1);
    }
}
