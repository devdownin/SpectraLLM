package fr.spectra.service.extraction;

import fr.spectra.model.ExtractedDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests unitaires de JsonExtractor avec les vrais fichiers de data/documents/jsons.zip.
 */
class JsonExtractorTest {

    /** Contenu des entrées du zip indexées par nom de fichier. */
    private static final Map<String, byte[]> ZIP_ENTRIES = new LinkedHashMap<>();

    private final JsonExtractor extractor = new JsonExtractor();

    /** true quand le corpus de production est présent (voir {@link KafkaCorpusFixture}). */
    private static boolean usingProductionCorpus;

    @BeforeAll
    static void loadCorpus() throws IOException {
        KafkaCorpusFixture.Corpus corpus = KafkaCorpusFixture.loadJson();
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
    void extract_firstEntry_contentTypeIsJson() throws Exception {
        Map.Entry<String, byte[]> first = firstEntry();
        ExtractedDocument doc = extractor.extract(first.getKey(), stream(first.getValue()));
        assertThat(doc.contentType()).isEqualTo("application/json");
    }

    @Test
    void extract_firstEntry_metadataFormatIsJson() throws Exception {
        Map.Entry<String, byte[]> first = firstEntry();
        ExtractedDocument doc = extractor.extract(first.getKey(), stream(first.getValue()));
        assertThat(doc.metadata()).containsEntry("format", "JSON");
    }

    @Test
    void extract_firstEntry_metadataHasElementCount() throws Exception {
        Map.Entry<String, byte[]> first = firstEntry();
        ExtractedDocument doc = extractor.extract(first.getKey(), stream(first.getValue()));
        // Les fichiers sont des tableaux Kafka → elementCount doit être présent
        assertThat(doc.metadata()).containsKey("elementCount");
        assertThat(Integer.parseInt(doc.metadata().get("elementCount"))).isGreaterThan(0);
    }

    @Test
    void extract_firstEntry_sourceFileMatchesName() throws Exception {
        Map.Entry<String, byte[]> first = firstEntry();
        ExtractedDocument doc = extractor.extract(first.getKey(), stream(first.getValue()));
        assertThat(doc.sourceFile()).isEqualTo(first.getKey());
    }

    // ── Tests sur le contenu métier (structure Kafka) ────────────────────────

    @Test
    void extract_firstEntry_textContainsKafkaTopicField() throws Exception {
        Map.Entry<String, byte[]> first = firstEntry();
        ExtractedDocument doc = extractor.extract(first.getKey(), stream(first.getValue()));
        assertThat(doc.text()).contains("topic:");
    }

    @Test
    void extract_firstEntry_textContainsPartitionField() throws Exception {
        Map.Entry<String, byte[]> first = firstEntry();
        ExtractedDocument doc = extractor.extract(first.getKey(), stream(first.getValue()));
        assertThat(doc.text()).contains("partition:");
    }

    @Test
    void extract_firstEntry_textContainsOffsetField() throws Exception {
        Map.Entry<String, byte[]> first = firstEntry();
        ExtractedDocument doc = extractor.extract(first.getKey(), stream(first.getValue()));
        assertThat(doc.text()).contains("offset:");
    }

    @Test
    void extract_firstEntry_textContainsKeyField() throws Exception {
        Map.Entry<String, byte[]> first = firstEntry();
        ExtractedDocument doc = extractor.extract(first.getKey(), stream(first.getValue()));
        assertThat(doc.text()).contains("key:");
    }

    @Test
    void extract_firstEntry_textContainsPayloadField() throws Exception {
        Map.Entry<String, byte[]> first = firstEntry();
        ExtractedDocument doc = extractor.extract(first.getKey(), stream(first.getValue()));
        assertThat(doc.text()).contains("payload:");
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
                    .containsEntry("format", "JSON");
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
    void extract_invalidJson_throwsExtractionException() {
        InputStream badInput = new ByteArrayInputStream("not valid json {{{".getBytes());
        assertThatThrownBy(() -> extractor.extract("bad.json", badInput))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("bad.json");
    }

    @Test
    void extract_emptyInput_doesNotThrow() throws Exception {
        // Jackson lit un InputStream vide comme un nœud absent (MissingNode) :
        // pas d'exception levée, le document est retourné sans crash.
        InputStream empty = new ByteArrayInputStream(new byte[0]);
        ExtractedDocument doc = extractor.extract("empty.json", empty);
        assertThat(doc).isNotNull();
        assertThat(doc.contentType()).isEqualTo("application/json");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map.Entry<String, byte[]> firstEntry() {
        return ZIP_ENTRIES.entrySet().iterator().next();
    }

    private InputStream stream(byte[] data) {
        return new ByteArrayInputStream(data);
    }
}
