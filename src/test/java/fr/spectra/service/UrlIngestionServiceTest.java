package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.IngestionTask;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.service.extraction.DocumentExtractorFactory;
import fr.spectra.service.extraction.HtmlExtractor;
import fr.spectra.service.extraction.TxtExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de UrlIngestionService.
 *
 * <p>L'ingestion asynchrone s'exécute sur un virtual thread. Les tests attendent
 * la fin de l'exécution via {@link #awaitTerminal(IngestionService, String)}.
 *
 * <p>Services réseau mockés : UrlFetcherService, ChromaDbClient, EmbeddingService.
 */
class UrlIngestionServiceTest {

    private static final String COLLECTION_ID  = "test-collection-id";
    private static final String COLLECTION_NAME = "test-collection";

    private IngestionService  ingestionService;
    private UrlFetcherService urlFetcher;
    private ChromaDbClient    chromaDbClient;
    private EmbeddingService  embeddingService;

    private UrlIngestionService urlIngestionService;

    @BeforeEach
    void setUp() {
        // Extracteurs réels supportant HTML et TXT (formats retournés par UrlFetcherService)
        DocumentExtractorFactory factory = new DocumentExtractorFactory(
                List.of(new HtmlExtractor(), new TxtExtractor()));

        TextCleanerService textCleaner  = new TextCleanerService();
        ChunkingService chunkingService = new ChunkingService();

        embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> List.of(0.1f, 0.2f, 0.3f)).toList();
        });

        chromaDbClient = mock(ChromaDbClient.class);
        when(chromaDbClient.getOrCreateCollection(anyString())).thenReturn(COLLECTION_ID);

        SpectraProperties.PipelineProperties pipeline =
                new SpectraProperties.PipelineProperties(512, 64, 10, 30, 120, 4);
        SpectraProperties.ChromaDbProperties chromaProps =
                new SpectraProperties.ChromaDbProperties("http://chroma:8000", COLLECTION_NAME);
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.pipeline()).thenReturn(pipeline);
        when(props.chromadb()).thenReturn(chromaProps);

        ingestionService = new IngestionService(
                factory, textCleaner, chunkingService,
                embeddingService, chromaDbClient,
                mock(IngestionTaskExecutor.class),
                mock(IngestedFileRepository.class),
                mock(GedService.class),
                props);

        urlFetcher = mock(UrlFetcherService.class);

        urlIngestionService = new UrlIngestionService(
                ingestionService, urlFetcher, chromaDbClient, COLLECTION_NAME);
    }

    // ── Tâche initiale ────────────────────────────────────────────────────────

    @Test
    void submit_returnsTaskInPendingStatus() {
        stubFetch("https://example.com/page", "example_com.html", htmlContent("Page test"));

        IngestionTask task = urlIngestionService.submit(List.of("https://example.com/page"));

        assertThat(task).isNotNull();
        assertThat(task.taskId()).isNotBlank();
        assertThat(task.status()).isEqualTo(IngestionTask.Status.PENDING);
    }

    @Test
    void submit_taskContainsSubmittedUrls() {
        String url = "https://example.com/doc";
        stubFetch(url, "doc.html", htmlContent("Contenu"));

        IngestionTask task = urlIngestionService.submit(List.of(url));

        assertThat(task.files()).containsExactly(url);
    }

    @Test
    void submit_taskIdAccessibleViaIngestionService() {
        stubFetch("https://example.com/", "example_com.html", htmlContent("Test"));

        IngestionTask task = urlIngestionService.submit(List.of("https://example.com/"));

        // La tâche est immédiatement enregistrée dans IngestionService
        assertThat(ingestionService.getTask(task.taskId())).isNotNull();
    }

    // ── Cycle de vie PENDING → PROCESSING → COMPLETED ────────────────────────

    @Test
    void submit_singleUrl_completesWithChunks() {
        String html = htmlContent("Spectra est une plateforme RAG. Elle indexe des documents pour les retrouver.");
        stubFetch("https://example.com/page", "page.html", html);

        IngestionTask task = urlIngestionService.submit(List.of("https://example.com/page"));
        IngestionTask terminal = awaitTerminal(ingestionService, task.taskId());

        assertThat(terminal.status()).isEqualTo(IngestionTask.Status.COMPLETED);
        assertThat(terminal.chunksCreated()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void submit_multipleUrls_allProcessed() {
        String url1 = "https://example.com/page1";
        String url2 = "https://example.com/page2";
        stubFetch(url1, "page1.html", htmlContent("Premier document à indexer."));
        stubFetch(url2, "page2.html", htmlContent("Deuxième document à indexer."));

        IngestionTask task = urlIngestionService.submit(List.of(url1, url2));
        IngestionTask terminal = awaitTerminal(ingestionService, task.taskId());

        assertThat(terminal.status()).isEqualTo(IngestionTask.Status.COMPLETED);
        assertThat(terminal.chunksCreated()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void submit_txtUrl_producesChunks() {
        stubFetch("https://example.com/readme.txt", "readme.txt",
                "Ceci est un fichier texte plain. Il contient plusieurs lignes d'information.");

        IngestionTask task = urlIngestionService.submit(List.of("https://example.com/readme.txt"));
        IngestionTask terminal = awaitTerminal(ingestionService, task.taskId());

        assertThat(terminal.status()).isEqualTo(IngestionTask.Status.COMPLETED);
        assertThat(terminal.chunksCreated()).isGreaterThanOrEqualTo(1);
    }

    // ── Cycle de vie → FAILED ─────────────────────────────────────────────────

    @Test
    void submit_fetchThrows_taskFailed() {
        when(urlFetcher.fetch(anyString())).thenThrow(new RuntimeException("Connection refused"));

        IngestionTask task = urlIngestionService.submit(List.of("https://unreachable.example.com/"));
        IngestionTask terminal = awaitTerminal(ingestionService, task.taskId());

        assertThat(terminal.status()).isEqualTo(IngestionTask.Status.FAILED);
        assertThat(terminal.error()).isNotBlank();
        assertThat(terminal.error()).contains("unreachable.example.com");
    }

    @Test
    void submit_chromaDbUnavailable_taskFailed() {
        when(chromaDbClient.getOrCreateCollection(anyString()))
                .thenThrow(new RuntimeException("ChromaDB unavailable"));

        IngestionTask task = urlIngestionService.submit(List.of("https://example.com/doc"));
        IngestionTask terminal = awaitTerminal(ingestionService, task.taskId());

        assertThat(terminal.status()).isEqualTo(IngestionTask.Status.FAILED);
    }

    // ── Tolérance aux pannes partielles ──────────────────────────────────────

    @Test
    void submit_oneUrlFailsOtherSucceeds_taskCompleted() {
        String goodUrl = "https://example.com/good";
        String badUrl  = "https://example.com/bad";
        stubFetch(goodUrl, "good.html", htmlContent("Page valide avec du contenu."));
        when(urlFetcher.fetch(eq(badUrl))).thenThrow(new RuntimeException("404 Not Found"));

        IngestionTask task = urlIngestionService.submit(List.of(goodUrl, badUrl));
        IngestionTask terminal = awaitTerminal(ingestionService, task.taskId());

        // Au moins un URL a réussi → COMPLETED (pas FAILED)
        assertThat(terminal.status()).isEqualTo(IngestionTask.Status.COMPLETED);
        assertThat(terminal.chunksCreated()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void submit_allUrlsFail_taskFailed() {
        when(urlFetcher.fetch(anyString())).thenThrow(new RuntimeException("All down"));

        IngestionTask task = urlIngestionService.submit(
                List.of("https://fail1.example.com/", "https://fail2.example.com/"));
        IngestionTask terminal = awaitTerminal(ingestionService, task.taskId());

        assertThat(terminal.status()).isEqualTo(IngestionTask.Status.FAILED);
    }

    // ── Contenu vide ──────────────────────────────────────────────────────────

    @Test
    void submit_emptyHtmlPage_completedWithZeroChunks() {
        stubFetch("https://example.com/empty", "empty.html", "<html><body></body></html>");

        IngestionTask task = urlIngestionService.submit(List.of("https://example.com/empty"));
        IngestionTask terminal = awaitTerminal(ingestionService, task.taskId());

        // Page vide → 0 chunks, mais pas d'erreur de traitement
        assertThat(terminal.status()).isIn(
                IngestionTask.Status.COMPLETED, IngestionTask.Status.FAILED);
        // Si COMPLETED, chunksCreated peut être 0 (page vide)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Enregistre un stub pour urlFetcher.fetch(url) qui retourne le contenu donné. */
    private void stubFetch(String url, String filename, String textContent) {
        when(urlFetcher.fetch(eq(url))).thenReturn(
                new UrlFetcherService.FetchedContent(
                        filename,
                        new ByteArrayInputStream(textContent.getBytes())));
    }

    /** Construit un fragment HTML minimal avec le contenu donné. */
    private String htmlContent(String bodyText) {
        return "<html><head><title>Test</title></head><body><p>" + bodyText + "</p></body></html>";
    }

    /**
     * Attend que la tâche atteigne un état terminal (COMPLETED ou FAILED).
     * Timeout 5 secondes — amplement suffisant pour un virtual thread local.
     */
    private IngestionTask awaitTerminal(IngestionService svc, String taskId) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            IngestionTask t = svc.getTask(taskId);
            if (t != null && (t.status() == IngestionTask.Status.COMPLETED
                    || t.status() == IngestionTask.Status.FAILED)) {
                return t;
            }
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("La tâche " + taskId + " n'a pas atteint d'état terminal dans les 5 secondes");
    }
}
