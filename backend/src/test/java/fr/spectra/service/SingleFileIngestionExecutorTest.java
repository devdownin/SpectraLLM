package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.IngestionTask;
import fr.spectra.service.extraction.DocumentExtractorFactory;
import fr.spectra.service.extraction.JsonExtractor;
import fr.spectra.service.extraction.TxtExtractor;
import fr.spectra.service.extraction.XmlExtractor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests du chemin d'ingestion d'un fichier unique (non-ZIP) dans
 * IngestionTaskExecutor : statut final, compte de chunks et callback
 * d'enregistrement GED.
 */
class SingleFileIngestionExecutorTest {

    @TempDir
    Path tempDir;

    private IngestionTaskExecutor executor;

    @BeforeEach
    void setUp() {
        DocumentExtractorFactory factory = new DocumentExtractorFactory(
                List.of(new JsonExtractor(), new XmlExtractor(), new TxtExtractor()));

        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> List.of(0.1f, 0.2f, 0.3f)).toList();
        });

        ChromaDbClient chromaDb = mock(ChromaDbClient.class);
        when(chromaDb.getOrCreateCollection(anyString())).thenReturn("test-col");

        FtsService ftsService = mock(FtsService.class);

        SpectraProperties.PipelineProperties pipeline = new SpectraProperties.PipelineProperties(
                512, 64, 10, 30, 120, 4);
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.pipeline()).thenReturn(pipeline);

        executor = new IngestionTaskExecutor(
                factory,
                new TextCleanerService(),
                new ChunkingService(props),
                embeddingService,
                chromaDb,
                ftsService,
                new SimpleMeterRegistry(),
                props,
                10,
                50,
                4);
    }

    private record Recorded(String hash, String fileName, int chunks) {}

    @Test
    void recordSubmissionRejected_incrementsCounterWithoutError() {
        // Compteur de contre-pression (429) — appelé par IngestionService quand le plafond
        // de tâches actives est atteint. Doit s'incrémenter sans lever.
        executor.recordSubmissionRejected();
        executor.recordSubmissionRejected();
    }

    @Test
    void execute_jsonFile_completesWithChunkCountAndRecords() throws Exception {
        Path json = tempDir.resolve("data.json");
        Files.writeString(json, "{\"name\":\"Spectra\",\"description\":\"moteur RAG\",\"version\":42}");

        Map<String, IngestionTask> tasks = new ConcurrentHashMap<>();
        String taskId = "task-json";
        tasks.put(taskId, IngestionTask.pending(taskId, List.of("data.json")));

        AtomicReference<Recorded> recorded = new AtomicReference<>();
        executor.execute(taskId, List.of("data.json"), List.of(json), tasks, "spectra_documents",
                Map.of(json, "hash-json-1"),
                (hash, fileName, chunks) -> recorded.set(new Recorded(hash, fileName, chunks)));

        IngestionTask finalTask = tasks.get(taskId);
        assertThat(finalTask.status()).isEqualTo(IngestionTask.Status.COMPLETED);
        // Le compte de chunks doit être renseigné en fin d'ingestion (bloc live stream).
        assertThat(finalTask.chunksCreated()).isGreaterThanOrEqualTo(1);

        // Le document doit être transmis au callback d'enregistrement GED.
        assertThat(recorded.get()).isNotNull();
        assertThat(recorded.get().hash()).isEqualTo("hash-json-1");
        assertThat(recorded.get().fileName()).isEqualTo("data.json");
        assertThat(recorded.get().chunks()).isEqualTo(finalTask.chunksCreated());
    }

    @Test
    void execute_partialBatchFailure_keepsCountAndRecordsDocument() throws Exception {
        // Embedding qui échoue à partir du 2ᵉ lot : le 1ᵉʳ lot a déjà été indexé.
        // Régression : le compte final ne doit PAS retomber à 0 et le document
        // (ex. JSON) doit tout de même être enregistré en GED pour ce qui a réussi.
        EmbeddingService flaky = mock(EmbeddingService.class);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(flaky.embedBatch(anyList())).thenAnswer(inv -> {
            if (calls.incrementAndGet() >= 2) {
                throw new RuntimeException("embedding service indisponible");
            }
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> List.of(0.1f, 0.2f, 0.3f)).toList();
        });

        DocumentExtractorFactory factory = new DocumentExtractorFactory(
                List.of(new JsonExtractor(), new XmlExtractor(), new TxtExtractor()));
        SpectraProperties.PipelineProperties pipeline = new SpectraProperties.PipelineProperties(
                512, 64, 1, 30, 120, 4);
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.pipeline()).thenReturn(pipeline);
        ChromaDbClient chromaDb = mock(ChromaDbClient.class);
        when(chromaDb.getOrCreateCollection(anyString())).thenReturn("test-col");

        // embeddingBatchSize = 1 → chaque chunk est un lot distinct (force ≥ 2 lots).
        IngestionTaskExecutor flakyExecutor = new IngestionTaskExecutor(
                factory, new TextCleanerService(), new ChunkingService(props),
                flaky, chromaDb, mock(FtsService.class), new SimpleMeterRegistry(),
                props, 1, 50, 4);

        // JSON volumineux → plusieurs chunks (≥ 2 lots).
        StringBuilder big = new StringBuilder("{");
        for (int i = 0; i < 400; i++) {
            if (i > 0) big.append(',');
            big.append("\"field").append(i).append("\":\"valeur de test relativement longue numéro ")
               .append(i).append("\"");
        }
        big.append("}");
        Path json = tempDir.resolve("flaky.json");
        Files.writeString(json, big.toString());

        Map<String, IngestionTask> tasks = new ConcurrentHashMap<>();
        String taskId = "task-flaky";
        tasks.put(taskId, IngestionTask.pending(taskId, List.of("flaky.json")));

        AtomicReference<Recorded> recorded = new AtomicReference<>();
        flakyExecutor.execute(taskId, List.of("flaky.json"), List.of(json), tasks, "spectra_documents",
                Map.of(json, "hash-flaky"),
                (hash, fileName, chunks) -> recorded.set(new Recorded(hash, fileName, chunks)));

        IngestionTask finalTask = tasks.get(taskId);
        assertThat(finalTask.status()).isEqualTo(IngestionTask.Status.COMPLETED);
        // Le 1ᵉʳ lot (1 chunk) a réussi avant l'échec → compte final = 1, pas 0.
        assertThat(finalTask.chunksCreated()).isEqualTo(1);
        // Le document est enregistré en GED malgré l'échec partiel.
        assertThat(recorded.get()).isNotNull();
        assertThat(recorded.get().chunks()).isEqualTo(1);
    }

    // ── Erreurs par fichier : plus de COMPLETED silencieux ────────────────────

    @Test
    void execute_unsupportedFile_marksTaskFailedWithFileError() throws Exception {
        Path png = tempDir.resolve("image.png");
        Files.write(png, new byte[]{1, 2, 3, 4});

        Map<String, IngestionTask> tasks = new ConcurrentHashMap<>();
        String taskId = "task-png";
        tasks.put(taskId, IngestionTask.pending(taskId, List.of("image.png")));

        executor.execute(taskId, List.of("image.png"), List.of(png), tasks, "spectra_documents",
                Map.of(png, "hash-png"), (hash, fileName, chunks) -> {});

        IngestionTask finalTask = tasks.get(taskId);
        // Tous les fichiers en échec → FAILED (et non plus COMPLETED avec 0 chunk,
        // indiscernable d'un succès), avec le détail par fichier.
        assertThat(finalTask.status()).isEqualTo(IngestionTask.Status.FAILED);
        assertThat(finalTask.fileErrors()).hasSize(1);
        assertThat(finalTask.fileErrors().getFirst()).contains("image.png");
    }

    @Test
    void execute_mixedSuccessAndFailure_completesWithFileErrors() throws Exception {
        Path json = tempDir.resolve("ok.json");
        Files.writeString(json, "{\"name\":\"Spectra\",\"description\":\"moteur RAG\"}");
        Path png = tempDir.resolve("bad.png");
        Files.write(png, new byte[]{1, 2, 3, 4});

        Map<String, IngestionTask> tasks = new ConcurrentHashMap<>();
        String taskId = "task-mixed";
        tasks.put(taskId, IngestionTask.pending(taskId, List.of("ok.json", "bad.png")));

        executor.execute(taskId, List.of("ok.json", "bad.png"), List.of(json, png), tasks,
                "spectra_documents", Map.of(json, "h-ok", png, "h-bad"), (hash, fileName, chunks) -> {});

        IngestionTask finalTask = tasks.get(taskId);
        // Succès partiel : la tâche aboutit mais l'échec du 2ᵉ fichier reste visible.
        assertThat(finalTask.status()).isEqualTo(IngestionTask.Status.COMPLETED);
        assertThat(finalTask.chunksCreated()).isGreaterThanOrEqualTo(1);
        assertThat(finalTask.fileErrors()).hasSize(1);
        assertThat(finalTask.fileErrors().getFirst()).contains("bad.png");
    }

    // ── Garde-fou mémoire : la limite s'applique aussi aux fichiers directs ───

    @Test
    void execute_oversizedFile_failsWithExplicitError() throws Exception {
        DocumentExtractorFactory factory = new DocumentExtractorFactory(
                List.of(new JsonExtractor(), new XmlExtractor(), new TxtExtractor()));
        SpectraProperties.PipelineProperties pipeline = new SpectraProperties.PipelineProperties(
                512, 64, 10, 30, 120, 4);
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.pipeline()).thenReturn(pipeline);
        ChromaDbClient chromaDb = mock(ChromaDbClient.class);
        when(chromaDb.getOrCreateCollection(anyString())).thenReturn("test-col");
        // Limite explicite de 1 Mo.
        IngestionTaskExecutor smallLimitExecutor = new IngestionTaskExecutor(
                factory, new TextCleanerService(), new ChunkingService(props),
                mock(EmbeddingService.class), chromaDb, mock(FtsService.class),
                new SimpleMeterRegistry(), props, 10, 1, 4);

        Path big = tempDir.resolve("trop-gros.txt");
        byte[] twoMb = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(twoMb, (byte) 'a');
        Files.write(big, twoMb);

        Map<String, IngestionTask> tasks = new ConcurrentHashMap<>();
        String taskId = "task-big-file";
        tasks.put(taskId, IngestionTask.pending(taskId, List.of("trop-gros.txt")));

        smallLimitExecutor.execute(taskId, List.of("trop-gros.txt"), List.of(big), tasks,
                "spectra_documents", Map.of(big, "hash-big"), (hash, fileName, chunks) -> {});

        IngestionTask finalTask = tasks.get(taskId);
        assertThat(finalTask.status()).isEqualTo(IngestionTask.Status.FAILED);
        assertThat(finalTask.fileErrors().getFirst()).contains("volumineux");
    }

    @Test
    void execute_jsonFile_finalChunkCountMatchesLiveProgress() throws Exception {
        // JSON volumineux → plusieurs chunks → la progression live doit converger
        // vers le total final renseigné par completed().
        StringBuilder big = new StringBuilder("{");
        for (int i = 0; i < 400; i++) {
            if (i > 0) big.append(',');
            big.append("\"field").append(i).append("\":\"valeur de test relativement longue numéro ")
               .append(i).append("\"");
        }
        big.append("}");
        Path json = tempDir.resolve("big.json");
        Files.writeString(json, big.toString());

        Map<String, IngestionTask> tasks = new ConcurrentHashMap<>();
        String taskId = "task-big-json";
        tasks.put(taskId, IngestionTask.pending(taskId, List.of("big.json")));

        AtomicInteger lastSeen = new AtomicInteger();
        executor.execute(taskId, List.of("big.json"), List.of(json), tasks, "spectra_documents",
                Map.of(json, "hash-json-2"),
                (hash, fileName, chunks) -> lastSeen.set(chunks));

        IngestionTask finalTask = tasks.get(taskId);
        assertThat(finalTask.status()).isEqualTo(IngestionTask.Status.COMPLETED);
        assertThat(finalTask.chunksCreated()).isGreaterThan(1);
        assertThat(lastSeen.get()).isEqualTo(finalTask.chunksCreated());
    }
}
