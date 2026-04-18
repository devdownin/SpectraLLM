package fr.spectra.service.extraction;

import fr.spectra.model.ExtractedDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests unitaires de HtmlExtractor.
 */
class HtmlExtractorTest {

    private final HtmlExtractor extractor = new HtmlExtractor();

    // ── supportedContentTypes ─────────────────────────────────────────────────

    @Test
    void supportedContentTypes_containsTextHtml() {
        assertThat(extractor.supportedContentTypes()).contains("text/html");
    }

    // ── extract — contenu de base ─────────────────────────────────────────────

    @Test
    void extract_simplePage_textNotBlank() throws Exception {
        String html = "<html><body><p>Bonjour le monde</p></body></html>";
        ExtractedDocument doc = extractor.extract("page.html", stream(html));
        assertThat(doc.text()).isNotBlank();
        assertThat(doc.text()).contains("Bonjour le monde");
    }

    @Test
    void extract_contentType_isTextHtml() throws Exception {
        ExtractedDocument doc = extractor.extract("test.html", stream("<html><body>X</body></html>"));
        assertThat(doc.contentType()).isEqualTo("text/html");
    }

    @Test
    void extract_sourceFilePreserved() throws Exception {
        ExtractedDocument doc = extractor.extract("ma_page.html", stream("<html><body>X</body></html>"));
        assertThat(doc.sourceFile()).isEqualTo("ma_page.html");
    }

    @Test
    void extract_titleIncludedInText() throws Exception {
        String html = "<html><head><title>Mon titre</title></head><body><p>Contenu</p></body></html>";
        ExtractedDocument doc = extractor.extract("page.html", stream(html));
        assertThat(doc.text()).contains("Mon titre");
    }

    @Test
    void extract_metadataContainsTitle() throws Exception {
        String html = "<html><head><title>Spectra</title></head><body><p>RAG</p></body></html>";
        ExtractedDocument doc = extractor.extract("page.html", stream(html));
        assertThat(doc.metadata()).containsEntry("title", "Spectra");
    }

    @Test
    void extract_metadataContentTypeIsHtml() throws Exception {
        ExtractedDocument doc = extractor.extract("page.html", stream("<html><body>X</body></html>"));
        assertThat(doc.metadata()).containsEntry("contentType", "text/html");
    }

    // ── Nettoyage des éléments non-contenu ────────────────────────────────────

    @Test
    void extract_scriptsRemoved() throws Exception {
        String html = "<html><body><script>alert('evil')</script><p>Contenu réel</p></body></html>";
        ExtractedDocument doc = extractor.extract("page.html", stream(html));
        assertThat(doc.text()).doesNotContain("alert");
        assertThat(doc.text()).contains("Contenu réel");
    }

    @Test
    void extract_stylesRemoved() throws Exception {
        String html = "<html><body><style>.x{color:red}</style><p>Texte visible</p></body></html>";
        ExtractedDocument doc = extractor.extract("page.html", stream(html));
        assertThat(doc.text()).doesNotContain("color:red");
        assertThat(doc.text()).contains("Texte visible");
    }

    @Test
    void extract_navRemoved() throws Exception {
        String html = "<html><body><nav>Menu Navigation</nav><p>Article principal</p></body></html>";
        ExtractedDocument doc = extractor.extract("page.html", stream(html));
        assertThat(doc.text()).doesNotContain("Menu Navigation");
        assertThat(doc.text()).contains("Article principal");
    }

    // ── Structure HTML ────────────────────────────────────────────────────────

    @Test
    void extract_headings_includedInText() throws Exception {
        String html = "<html><body><h1>Titre principal</h1><h2>Sous-titre</h2></body></html>";
        ExtractedDocument doc = extractor.extract("page.html", stream(html));
        assertThat(doc.text()).contains("Titre principal");
        assertThat(doc.text()).contains("Sous-titre");
    }

    @Test
    void extract_listItems_includedInText() throws Exception {
        String html = "<html><body><ul><li>Item A</li><li>Item B</li></ul></body></html>";
        ExtractedDocument doc = extractor.extract("page.html", stream(html));
        assertThat(doc.text()).contains("Item A");
        assertThat(doc.text()).contains("Item B");
    }

    @Test
    void extract_tableContent_includedInText() throws Exception {
        String html = "<html><body><table><tr><td>Cellule</td></tr></table></body></html>";
        ExtractedDocument doc = extractor.extract("page.html", stream(html));
        assertThat(doc.text()).contains("Cellule");
    }

    // ── Page vide / minimale ──────────────────────────────────────────────────

    @Test
    void extract_emptyBody_textIsBlankOrEmpty() throws Exception {
        String html = "<html><head><title></title></head><body></body></html>";
        ExtractedDocument doc = extractor.extract("empty.html", stream(html));
        // Pas d'exception — texte peut être vide
        assertThat(doc).isNotNull();
    }

    @Test
    void extract_pageCountIsOne() throws Exception {
        ExtractedDocument doc = extractor.extract("p.html", stream("<html><body><p>X</p></body></html>"));
        assertThat(doc.pageCount()).isEqualTo(1);
    }

    // ── Erreur ────────────────────────────────────────────────────────────────

    @Test
    void extract_ioError_throwsExtractionException() {
        var brokenStream = new java.io.InputStream() {
            @Override public int read() throws java.io.IOException {
                throw new java.io.IOException("broken pipe");
            }
        };
        assertThatThrownBy(() -> extractor.extract("bad.html", brokenStream))
                .isInstanceOf(ExtractionException.class);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private ByteArrayInputStream stream(String html) {
        return new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));
    }
}
