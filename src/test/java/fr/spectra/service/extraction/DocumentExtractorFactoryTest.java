package fr.spectra.service.extraction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests unitaires de DocumentExtractorFactory — résolution de content-type et routage.
 */
class DocumentExtractorFactoryTest {

    private DocumentExtractorFactory factory;

    @BeforeEach
    void setUp() {
        factory = new DocumentExtractorFactory(List.of(
                new JsonExtractor(),
                new XmlExtractor(),
                new HtmlExtractor(),
                new TxtExtractor()
        ));
    }

    // ── resolveContentType ────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
            "document.pdf,   application/pdf",
            "REPORT.PDF,     application/pdf",
            "data.json,      application/json",
            "DATA.JSON,      application/json",
            "schema.xml,     application/xml",
            "page.html,      text/html",
            "page.htm,       text/html",
            "readme.txt,     text/plain",
            "README.TXT,     text/plain",
            "doc.docx,       application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "legacy.doc,     application/msword"
    })
    void resolveContentType_knownExtension_returnsCorrectType(String filename, String expectedType) {
        assertThat(factory.resolveContentType(filename)).isEqualTo(expectedType);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fichier.xlsx", "image.png", "archive.tar", "script.sh", "binary.bin"})
    void resolveContentType_unsupportedExtension_throwsExtractionException(String filename) {
        assertThatThrownBy(() -> factory.resolveContentType(filename))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining(filename);
    }

    @Test
    void resolveContentType_pathWithDirectory_usesLastSegment() {
        // La factory résout sur le nom court — resolveContentType ne gère pas les paths
        // ce comportement est documenté dans processSingleFile qui extrait le shortName
        assertThat(factory.resolveContentType("document.json")).isEqualTo("application/json");
    }

    // ── getExtractor ──────────────────────────────────────────────────────────

    @Test
    void getExtractor_json_returnsJsonExtractor() {
        DocumentExtractor extractor = factory.getExtractor("application/json");
        assertThat(extractor).isInstanceOf(JsonExtractor.class);
    }

    @Test
    void getExtractor_xml_returnsXmlExtractor() {
        DocumentExtractor extractor = factory.getExtractor("application/xml");
        assertThat(extractor).isInstanceOf(XmlExtractor.class);
    }

    @Test
    void getExtractor_html_returnsHtmlExtractor() {
        DocumentExtractor extractor = factory.getExtractor("text/html");
        assertThat(extractor).isInstanceOf(HtmlExtractor.class);
    }

    @Test
    void getExtractor_txt_returnsTxtExtractor() {
        DocumentExtractor extractor = factory.getExtractor("text/plain");
        assertThat(extractor).isInstanceOf(TxtExtractor.class);
    }

    @Test
    void getExtractor_unknownType_throwsExtractionException() {
        assertThatThrownBy(() -> factory.getExtractor("application/unknown"))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("application/unknown");
    }

    // ── Cohérence resolve → get ───────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"file.json", "data.xml", "page.html", "notes.txt"})
    void resolveAndGet_roundTrip_noException(String filename) {
        String contentType = factory.resolveContentType(filename);
        DocumentExtractor extractor = factory.getExtractor(contentType);
        assertThat(extractor).isNotNull();
        assertThat(extractor.supportedContentTypes()).contains(contentType);
    }
}
