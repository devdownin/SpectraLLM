package fr.spectra.service.extraction;

import fr.spectra.model.ExtractedDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires de TxtExtractor.
 */
class TxtExtractorTest {

    private final TxtExtractor extractor = new TxtExtractor();

    @Test
    void supportedContentTypes_containsTextPlain() {
        assertThat(extractor.supportedContentTypes()).contains("text/plain");
    }

    @Test
    void extract_simpleText_returnsContent() throws Exception {
        ExtractedDocument doc = extractor.extract("readme.txt", stream("Bonjour le monde"));
        assertThat(doc.text()).isEqualTo("Bonjour le monde");
    }

    @Test
    void extract_multilineText_preservesLines() throws Exception {
        String text = "ligne un\nligne deux\nligne trois";
        ExtractedDocument doc = extractor.extract("file.txt", stream(text));
        assertThat(doc.text()).contains("ligne un");
        assertThat(doc.text()).contains("ligne deux");
        assertThat(doc.text()).contains("ligne trois");
    }

    @Test
    void extract_contentType_isTextPlain() throws Exception {
        ExtractedDocument doc = extractor.extract("file.txt", stream("texte"));
        assertThat(doc.contentType()).isEqualTo("text/plain");
    }

    @Test
    void extract_sourceFilePreserved() throws Exception {
        ExtractedDocument doc = extractor.extract("mon_fichier.txt", stream("contenu"));
        assertThat(doc.sourceFile()).isEqualTo("mon_fichier.txt");
    }

    @Test
    void extract_pageCountIsOne() throws Exception {
        ExtractedDocument doc = extractor.extract("f.txt", stream("x"));
        assertThat(doc.pageCount()).isEqualTo(1);
    }

    @Test
    void extract_metadataContainsSourceFile() throws Exception {
        ExtractedDocument doc = extractor.extract("notes.txt", stream("notes"));
        assertThat(doc.metadata()).containsEntry("sourceFile", "notes.txt");
    }

    @Test
    void extract_emptyFile_returnsEmptyText() throws Exception {
        ExtractedDocument doc = extractor.extract("empty.txt", stream(""));
        assertThat(doc.text()).isEmpty();
    }

    @Test
    void extract_utf8Content_preservesAccents() throws Exception {
        String text = "éàü çœæ — caractères accentués";
        ExtractedDocument doc = extractor.extract("utf8.txt", stream(text));
        assertThat(doc.text()).isEqualTo(text);
    }

    private ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
