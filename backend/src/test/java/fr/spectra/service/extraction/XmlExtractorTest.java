package fr.spectra.service.extraction;

import fr.spectra.model.ExtractedDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests unitaires de XmlExtractor avec les vrais fichiers de data/documents/xmls.zip.
 */
class XmlExtractorTest {

    private static final Path ZIP_PATH = Path.of("data/documents/xmls.zip");

    /** Contenu des entrées du zip indexées par nom de fichier. */
    private static final Map<String, byte[]> ZIP_ENTRIES = new HashMap<>();

    private final XmlExtractor extractor = new XmlExtractor();

    @BeforeAll
    static void loadZipEntries() throws IOException {
        assumeTrue(Files.exists(ZIP_PATH), "xmls.zip absent — test ignoré");
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(ZIP_PATH))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    ZIP_ENTRIES.put(entry.getName(), zis.readAllBytes());
                }
            }
        }
        assumeTrue(!ZIP_ENTRIES.isEmpty(), "xmls.zip vide — test ignoré");
    }

    // ── Tests structurels sur le premier fichier ──────────────────────────────

    @Test
    void extract_firstEntry_textIsNotBlank() throws Exception {
        Map.Entry<String, byte[]> first = firstEntry();
        ExtractedDocument doc = extractor.extract(first.getKey(), stream(first.getValue()));
        assertThat(doc.text()).isNotBlank();
    }

    @Test
    void extract_firstEntry_contentTypeIsXml() throws Exception {
        Map.Entry<String, byte[]> first = firstEntry();
        ExtractedDocument doc = extractor.extract(first.getKey(), stream(first.getValue()));
        assertThat(doc.contentType()).isEqualTo("application/xml");
    }

    @Test
    void extract_firstEntry_metadataFormatIsXml() throws Exception {
        Map.Entry<String, byte[]> first = firstEntry();
        ExtractedDocument doc = extractor.extract(first.getKey(), stream(first.getValue()));
        assertThat(doc.metadata()).containsEntry("format", "XML");
    }

    @Test
    void extract_firstEntry_metadataHasRootElement() throws Exception {
        Map.Entry<String, byte[]> first = firstEntry();
        ExtractedDocument doc = extractor.extract(first.getKey(), stream(first.getValue()));
        assertThat(doc.metadata()).containsKey("rootElement");
        assertThat(doc.metadata().get("rootElement")).isNotBlank();
    }

    @Test
    void extract_firstEntry_rootElementIsKafkaMessageList() throws Exception {
        Map.Entry<String, byte[]> first = firstEntry();
        ExtractedDocument doc = extractor.extract(first.getKey(), stream(first.getValue()));
        // Tous les fichiers du zip ont KafkaMessageList comme racine
        assertThat(doc.metadata().get("rootElement")).isEqualTo("KafkaMessageList");
    }

    @Test
    void extract_firstEntry_sourceFileMatchesName() throws Exception {
        Map.Entry<String, byte[]> first = firstEntry();
        ExtractedDocument doc = extractor.extract(first.getKey(), stream(first.getValue()));
        assertThat(doc.sourceFile()).isEqualTo(first.getKey());
    }

    // ── Tests sur le contenu métier (structure Kafka en XML) ─────────────────

    @Test
    void extract_firstEntry_textContainsTopicPath() throws Exception {
        Map.Entry<String, byte[]> first = firstEntry();
        ExtractedDocument doc = extractor.extract(first.getKey(), stream(first.getValue()));
        // Le chemin aplati doit contenir le nœud topic
        assertThat(doc.text()).contains("topic:");
    }

    @Test
    void extract_firstEntry_textContainsTopicValue() throws Exception {
        Map.Entry<String, byte[]> first = firstEntry();
        ExtractedDocument doc = extractor.extract(first.getKey(), stream(first.getValue()));
        assertThat(doc.text()).contains("asf.peage.backoffice.conformite.sortie.conforme");
    }

    @Test
    void extract_firstEntry_textContainsPayload() throws Exception {
        Map.Entry<String, byte[]> first = firstEntry();
        ExtractedDocument doc = extractor.extract(first.getKey(), stream(first.getValue()));
        assertThat(doc.text()).contains("payload:");
    }

    @Test
    void extract_firstEntry_textUsesXmlPathNotation() throws Exception {
        Map.Entry<String, byte[]> first = firstEntry();
        ExtractedDocument doc = extractor.extract(first.getKey(), stream(first.getValue()));
        // Le XmlExtractor aplatit les chemins avec des points
        assertThat(doc.text()).contains("KafkaMessageList.");
    }

    // ── Tests sur l'ensemble des 36 fichiers ─────────────────────────────────

    @Test
    void extract_allEntries_noneProducesBlankText() throws Exception {
        for (Map.Entry<String, byte[]> entry : ZIP_ENTRIES.entrySet()) {
            ExtractedDocument doc = extractor.extract(entry.getKey(), stream(entry.getValue()));
            assertThat(doc.text())
                    .as("Texte vide pour: %s", entry.getKey())
                    .isNotBlank();
        }
    }

    @Test
    void extract_allEntries_allHaveFormatMetadata() throws Exception {
        for (Map.Entry<String, byte[]> entry : ZIP_ENTRIES.entrySet()) {
            ExtractedDocument doc = extractor.extract(entry.getKey(), stream(entry.getValue()));
            assertThat(doc.metadata())
                    .as("Métadonnées manquantes pour: %s", entry.getKey())
                    .containsEntry("format", "XML");
        }
    }

    @Test
    void extract_allEntries_allHaveKafkaMessageListRoot() throws Exception {
        for (Map.Entry<String, byte[]> entry : ZIP_ENTRIES.entrySet()) {
            ExtractedDocument doc = extractor.extract(entry.getKey(), stream(entry.getValue()));
            assertThat(doc.metadata().get("rootElement"))
                    .as("rootElement incorrect pour: %s", entry.getKey())
                    .isEqualTo("KafkaMessageList");
        }
    }

    @Test
    void extract_allEntries_count() {
        assertThat(ZIP_ENTRIES).hasSize(36);
    }

    // ── Tests d'erreur ────────────────────────────────────────────────────────

    @Test
    void extract_invalidXml_throwsExtractionException() {
        InputStream badInput = new ByteArrayInputStream("<unclosed>".getBytes());
        assertThatThrownBy(() -> extractor.extract("bad.xml", badInput))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("bad.xml");
    }

    @Test
    void extract_emptyInput_throwsExtractionException() {
        InputStream empty = new ByteArrayInputStream(new byte[0]);
        assertThatThrownBy(() -> extractor.extract("empty.xml", empty))
                .isInstanceOf(ExtractionException.class);
    }

    @Test
    void extract_xxeDoctype_throwsExtractionException() {
        // Vérification de la protection XXE : les DOCTYPE sont interdits
        String xxePayload = "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><root>&xxe;</root>";
        InputStream xxeInput = new ByteArrayInputStream(xxePayload.getBytes());
        assertThatThrownBy(() -> extractor.extract("xxe.xml", xxeInput))
                .isInstanceOf(ExtractionException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map.Entry<String, byte[]> firstEntry() {
        return ZIP_ENTRIES.entrySet().iterator().next();
    }

    private InputStream stream(byte[] data) {
        return new ByteArrayInputStream(data);
    }
}
