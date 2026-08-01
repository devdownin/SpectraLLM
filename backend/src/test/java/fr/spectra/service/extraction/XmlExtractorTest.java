package fr.spectra.service.extraction;

import fr.spectra.model.ExtractedDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests unitaires de XmlExtractor avec les vrais fichiers de data/documents/xmls.zip.
 */
class XmlExtractorTest {

    /** Contenu des entrées du zip indexées par nom de fichier. */
    private static final Map<String, byte[]> ZIP_ENTRIES = new LinkedHashMap<>();

    private final XmlExtractor extractor = new XmlExtractor();

    /** true quand le corpus de production est présent (voir {@link KafkaCorpusFixture}). */
    private static boolean usingProductionCorpus;

    @BeforeAll
    static void loadCorpus() throws IOException {
        KafkaCorpusFixture.Corpus corpus = KafkaCorpusFixture.loadXml();
        ZIP_ENTRIES.putAll(corpus.entries());
        usingProductionCorpus = corpus.isProduction();
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
        assertThat(doc.text()).contains(KafkaCorpusFixture.TOPIC);
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
        // Seule assertion portant sur les DONNÉES et non sur le code : elle n'a de sens
        // que face au corpus de production. Le saut est ici dans le corps du test, donc
        // compté et affiché — contrairement à un assumeTrue en @BeforeAll, qui faisait
        // disparaître la classe entière du rapport.
        assumeTrue(usingProductionCorpus,
                "corpus de production absent — cardinalité non vérifiable");
        assertThat(ZIP_ENTRIES).hasSize(KafkaCorpusFixture.PRODUCTION_ENTRY_COUNT);
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
