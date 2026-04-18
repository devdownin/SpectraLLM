package fr.spectra.service;

import fr.spectra.service.extraction.DocumentExtractorFactory;
import fr.spectra.service.extraction.JsonExtractor;
import fr.spectra.service.extraction.XmlExtractor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de l'ingestion ZIP dans IngestionTaskExecutor.
 * Vérifie que les archives sont correctement dépaquetées et traitées
 * via le chemin asynchrone (ingestZip), incluant l'indexation FTS.
 */
class ZipIngestionExecutorTest {

    private static final String SAMPLE_JSON =
            "[{\"topic\":\"test\",\"payload\":\"{\\\"key\\\":\\\"value\\\"}\"}]";

    private static final String SAMPLE_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<root><item><name>test</name><value>42</value></item></root>";

    private IngestionTaskExecutor executor;

    @BeforeEach
    void setUp() {
        DocumentExtractorFactory factory = new DocumentExtractorFactory(
                List.of(new JsonExtractor(), new XmlExtractor()));

        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> List.of(0.1f, 0.2f, 0.3f)).toList();
        });

        ChromaDbClient chromaDb = mock(ChromaDbClient.class);
        when(chromaDb.getOrCreateCollection(anyString())).thenReturn("test-col");

        FtsService ftsService = mock(FtsService.class);

        executor = new IngestionTaskExecutor(
                factory,
                new TextCleanerService(),
                new ChunkingService(),
                embeddingService,
                chromaDb,
                ftsService,
                new SimpleMeterRegistry(),
                10);
    }

    // ── Contenu JSON ──────────────────────────────────────────────────────────

    @Test
    void ingestZip_singleJsonEntry_producesAtLeastOneChunk() throws Exception {
        byte[] zip = buildZip(Map.of("sample.json", SAMPLE_JSON.getBytes()));
        int chunks = executor.ingestZip(stream(zip), "sample.zip", "test-col", "test");
        assertThat(chunks).isGreaterThanOrEqualTo(1);
    }

    @Test
    void ingestZip_multipleJsonEntries_aggregatesChunks() throws Exception {
        byte[] zip = buildZip(Map.of(
                "a.json", SAMPLE_JSON.getBytes(),
                "b.json", SAMPLE_JSON.replace("value", "other").getBytes(),
                "c.json", SAMPLE_JSON.replace("value", "third").getBytes()
        ));
        int chunks = executor.ingestZip(stream(zip), "multi.zip", "test-col", "test");
        assertThat(chunks).isGreaterThanOrEqualTo(3);
    }

    // ── Contenu XML ───────────────────────────────────────────────────────────

    @Test
    void ingestZip_singleXmlEntry_producesAtLeastOneChunk() throws Exception {
        byte[] zip = buildZip(Map.of("sample.xml", SAMPLE_XML.getBytes()));
        int chunks = executor.ingestZip(stream(zip), "sample.zip", "test-col", "test");
        assertThat(chunks).isGreaterThanOrEqualTo(1);
    }

    // ── Mix formats ───────────────────────────────────────────────────────────

    @Test
    void ingestZip_mixedJsonXml_processesBothTypes() throws Exception {
        byte[] zip = buildZip(Map.of(
                "data.json", SAMPLE_JSON.getBytes(),
                "data.xml",  SAMPLE_XML.getBytes()
        ));
        int chunks = executor.ingestZip(stream(zip), "mixed.zip", "test-col", "test");
        assertThat(chunks).isGreaterThanOrEqualTo(2);
    }

    // ── ZIP imbriqué ──────────────────────────────────────────────────────────

    @Test
    void ingestZip_nestedZip_isRecursivelyProcessed() throws Exception {
        byte[] innerZip = buildZip(Map.of("inner.json", SAMPLE_JSON.getBytes()));
        byte[] outerZip = buildZip(Map.of("archive.zip", innerZip));
        int chunks = executor.ingestZip(stream(outerZip), "outer.zip", "test-col", "test");
        assertThat(chunks).isGreaterThanOrEqualTo(1);
    }

    // ── Cas limites ───────────────────────────────────────────────────────────

    @Test
    void ingestZip_emptyZip_returnsZero() throws Exception {
        byte[] zip = buildZip(Map.of());
        int chunks = executor.ingestZip(stream(zip), "empty.zip", "test-col", "test");
        assertThat(chunks).isZero();
    }

    @Test
    void ingestZip_unsupportedFilesOnly_returnsZero() throws Exception {
        byte[] zip = buildZip(Map.of(
                "image.png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47},
                "archive.tar", new byte[]{1, 2, 3}
        ));
        int chunks = executor.ingestZip(stream(zip), "unsupported.zip", "test-col", "test");
        assertThat(chunks).isZero();
    }

    @Test
    void ingestZip_macosxEntriesFiltered_notProcessed() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("__MACOSX/._data.json"));
            zos.write(SAMPLE_JSON.getBytes());
            zos.closeEntry();
        }
        int chunks = executor.ingestZip(stream(baos.toByteArray()), "mac.zip", "test-col", "test");
        assertThat(chunks).isZero();
    }

    @Test
    void ingestZip_directoryEntries_skippedWithoutError() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("subdir/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("subdir/data.json"));
            zos.write(SAMPLE_JSON.getBytes());
            zos.closeEntry();
        }
        int chunks = executor.ingestZip(stream(baos.toByteArray()), "nested.zip", "test-col", "test");
        assertThat(chunks).isGreaterThanOrEqualTo(1);
    }

    @Test
    void ingestZip_mixedSupportedAndUnsupported_processesOnlySupported() throws Exception {
        byte[] zip = buildZip(Map.of(
                "keep.json", SAMPLE_JSON.getBytes(),
                "skip.bin",  new byte[]{1, 2, 3},
                "keep.xml",  SAMPLE_XML.getBytes()
        ));
        int chunks = executor.ingestZip(stream(zip), "partial.zip", "test-col", "test");
        assertThat(chunks).isGreaterThanOrEqualTo(2);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static byte[] buildZip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private InputStream stream(byte[] data) {
        return new ByteArrayInputStream(data);
    }
}
