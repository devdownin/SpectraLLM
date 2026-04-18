package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.service.extraction.DocumentExtractorFactory;
import fr.spectra.service.extraction.JsonExtractor;
import fr.spectra.service.extraction.XmlExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests d'intégration bout-en-bout de l'ingestion de zips JSON et XML.
 *
 * <p>Les tests "pipeline réel" utilisent des données synthétiques qui reproduisent
 * fidèlement la structure des fichiers Kafka dans {@code data/documents/}.
 * Les tests "smoke" sur les vrais zips traitent uniquement la plus petite entrée
 * de chaque archive pour éviter la saturation heap (GC + Mockito + Jackson).
 *
 * <p>Services réseau mockés : EmbeddingService, ChromaDbClient.
 */
class ZipIngestionTest {

    private static final Path JSONS_ZIP = Path.of("data/documents/jsons.zip");
    private static final Path XMLS_ZIP  = Path.of("data/documents/xmls.zip");

    // JSON Kafka réaliste (1 message) — structure identique aux vrais fichiers
    private static final String SAMPLE_JSON =
            "[{\"topic\":\"asf.peage.backoffice.conformite.sortie.conforme\"," +
            "\"partition\":0,\"offset\":559253,\"key\":\"2500454522\"," +
            "\"payload\":\"{\\\"passageSortie\\\":{\\\"paiement\\\":{\\\"retenus\\\":" +
            "[{\\\"montant\\\":0,\\\"devise\\\":\\\"E\\\",\\\"type\\\":\\\"passageForce\\\"}]}," +
            "\\\"tarification\\\":{\\\"trajet\\\":{\\\"type\\\":\\\"NORMAL\\\"," +
            "\\\"sortie\\\":{\\\"gare\\\":\\\"25004545\\\",\\\"voie\\\":\\\"22\\\"}," +
            "\\\"kilometrage\\\":9}},\\\"DHM\\\":\\\"2026-03-27T15:10:04.620+01:00\\\"," +
            "\\\"etat\\\":\\\"complet\\\"}," +
            "\\\"idPassage\\\":\\\"25004545-20260327141004250045452200006\\\"," +
            "\\\"modeFonctionnement\\\":\\\"exploitation\\\"}\"" +
            "}]";

    // XML Kafka réaliste — structure identique aux vrais fichiers
    private static final String SAMPLE_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<KafkaMessageList><messages><messages>" +
            "<topic>asf.peage.backoffice.conformite.sortie.conforme</topic>" +
            "<partition>0</partition><offset>559253</offset>" +
            "<key>2500454522</key>" +
            "<payload>{\"passageSortie\":{\"paiement\":{\"retenus\":[{\"montant\":0," +
            "\"devise\":\"E\",\"type\":\"passageForce\"}]},\"tarification\":{\"trajet\":{" +
            "\"type\":\"NORMAL\",\"sortie\":{\"gare\":\"25004545\",\"voie\":\"22\"}," +
            "\"kilometrage\":9}},\"DHM\":\"2026-03-27T15:10:04.620+01:00\"," +
            "\"etat\":\"complet\"},\"idPassage\":\"25004545-20260327141004250045452200006\"," +
            "\"modeFonctionnement\":\"exploitation\"}</payload>" +
            "</messages></messages></KafkaMessageList>";

    private IngestionService ingestionService;

    @BeforeEach
    void setUp() {
        DocumentExtractorFactory factory = new DocumentExtractorFactory(
                List.of(new JsonExtractor(), new XmlExtractor()));

        TextCleanerService textCleaner  = new TextCleanerService();
        ChunkingService chunkingService = new ChunkingService();

        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> List.of(0.1f, 0.2f, 0.3f)).toList();
        });

        ChromaDbClient chromaDb = mock(ChromaDbClient.class);
        when(chromaDb.getOrCreateCollection(anyString())).thenReturn("test-collection");

        SpectraProperties.PipelineProperties pipeline =
                new SpectraProperties.PipelineProperties(512, 64, 10, 30, 120, 4);
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.pipeline()).thenReturn(pipeline);
        when(props.chromadb()).thenReturn(null);

        ingestionService = new IngestionService(
                factory, textCleaner, chunkingService,
                embeddingService, chromaDb,
                mock(IngestionTaskExecutor.class),
                mock(IngestedFileRepository.class),
                mock(GedService.class),
                props);
    }

    // ── Pipeline JSON synthétique ─────────────────────────────────────────────

    @Test
    void processZip_singleJsonEntry_producesAtLeastOneChunk() throws Exception {
        byte[] zip = buildZip(Map.of("sample.json", SAMPLE_JSON.getBytes()));
        int chunks = ingestionService.processZip(stream(zip), "sample.zip", "test-collection");
        assertThat(chunks).isGreaterThanOrEqualTo(1);
    }

    @Test
    void processZip_multipleJsonEntries_producesChunksForEach() throws Exception {
        byte[] zip = buildZip(Map.of(
                "msg1.json", SAMPLE_JSON.getBytes(),
                "msg2.json", SAMPLE_JSON.replace("559253", "559254").getBytes(),
                "msg3.json", SAMPLE_JSON.replace("559253", "559255").getBytes()
        ));
        int chunks = ingestionService.processZip(stream(zip), "multi.zip", "test-collection");
        // 3 fichiers → au moins 3 chunks (1 par fichier minimum)
        assertThat(chunks).isGreaterThanOrEqualTo(3);
    }

    // ── Pipeline XML synthétique ──────────────────────────────────────────────

    @Test
    void processZip_singleXmlEntry_producesAtLeastOneChunk() throws Exception {
        byte[] zip = buildZip(Map.of("sample.xml", SAMPLE_XML.getBytes()));
        int chunks = ingestionService.processZip(stream(zip), "sample.zip", "test-collection");
        assertThat(chunks).isGreaterThanOrEqualTo(1);
    }

    @Test
    void processZip_multipleXmlEntries_producesChunksForEach() throws Exception {
        byte[] zip = buildZip(Map.of(
                "msg1.xml", SAMPLE_XML.getBytes(),
                "msg2.xml", SAMPLE_XML.replace("559253", "559254").getBytes()
        ));
        int chunks = ingestionService.processZip(stream(zip), "multi.zip", "test-collection");
        assertThat(chunks).isGreaterThanOrEqualTo(2);
    }

    // ── Mix JSON + XML dans le même zip ──────────────────────────────────────

    @Test
    void processZip_mixedFormats_processesBothTypes() throws Exception {
        byte[] zip = buildZip(Map.of(
                "data.json", SAMPLE_JSON.getBytes(),
                "data.xml",  SAMPLE_XML.getBytes()
        ));
        int chunks = ingestionService.processZip(stream(zip), "mixed.zip", "test-collection");
        assertThat(chunks).isGreaterThanOrEqualTo(2);
    }

    // ── Smoke tests sur les vrais zips (entrée la plus petite seulement) ─────

    @Test
    void processZip_realJsonsZip_smallestEntry_producesChunks() throws Exception {
        assumeTrue(Files.exists(JSONS_ZIP), "jsons.zip absent — test ignoré");
        byte[] smallestEntry = extractSmallestEntry(JSONS_ZIP);
        assumeTrue(smallestEntry != null, "Aucune entrée JSON trouvée");

        byte[] miniZip = buildZip(Map.of("real_sample.json", smallestEntry));
        int chunks = ingestionService.processZip(stream(miniZip), "jsons.zip", "test-collection");

        assertThat(chunks).isGreaterThanOrEqualTo(1);
    }

    @Test
    void processZip_realXmlsZip_smallestEntry_producesChunks() throws Exception {
        assumeTrue(Files.exists(XMLS_ZIP), "xmls.zip absent — test ignoré");
        byte[] smallestEntry = extractSmallestEntry(XMLS_ZIP);
        assumeTrue(smallestEntry != null, "Aucune entrée XML trouvée");

        byte[] miniZip = buildZip(Map.of("real_sample.xml", smallestEntry));
        int chunks = ingestionService.processZip(stream(miniZip), "xmls.zip", "test-collection");

        assertThat(chunks).isGreaterThanOrEqualTo(1);
    }

    // ── Cas limites ───────────────────────────────────────────────────────────

    @Test
    void processZip_emptyZip_returnsZeroChunks() throws Exception {
        byte[] zip = buildZip(Map.of());
        int chunks = ingestionService.processZip(stream(zip), "empty.zip", "test-collection");
        assertThat(chunks).isZero();
    }

    @Test
    void processZip_zipWithUnsupportedFiles_returnsZeroChunks() throws Exception {
        byte[] zip = buildZip(Map.of("ignored.bin", new byte[]{1, 2, 3}));
        int chunks = ingestionService.processZip(stream(zip), "unsupported.zip", "test-collection");
        assertThat(chunks).isZero();
    }

    @Test
    void processZip_zipSkipsMacosxEntries() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("__MACOSX/._something.json"));
            zos.write(SAMPLE_JSON.getBytes());
            zos.closeEntry();
        }
        int chunks = ingestionService.processZip(stream(baos.toByteArray()), "mac.zip", "test-collection");
        // Les entrées __MACOSX sont filtrées
        assertThat(chunks).isZero();
    }

    @Test
    void processZip_directoryEntriesIgnored() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("subdir/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("subdir/data.json"));
            zos.write(SAMPLE_JSON.getBytes());
            zos.closeEntry();
        }
        int chunks = ingestionService.processZip(stream(baos.toByteArray()), "nested.zip", "test-collection");
        // Le répertoire est ignoré, le fichier JSON est traité
        assertThat(chunks).isGreaterThanOrEqualTo(1);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Construit un zip en mémoire à partir d'une map nom → contenu. */
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

    /** Extrait le contenu de la plus petite entrée d'un zip réel. */
    private static byte[] extractSmallestEntry(Path zipPath) throws Exception {
        byte[] smallest = null;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    byte[] data = zis.readAllBytes();
                    if (smallest == null || data.length < smallest.length) {
                        smallest = data;
                    }
                }
            }
        }
        return smallest;
    }

    private InputStream stream(byte[] data) {
        return new ByteArrayInputStream(data);
    }
}
