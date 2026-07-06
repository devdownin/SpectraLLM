package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.persistence.IngestedFileEntity;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.persistence.StreamSourceRepository;
import fr.spectra.service.extraction.DocumentExtractorFactory;
import fr.spectra.service.extraction.TxtExtractor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Filet de non-régression ciblé sur le contrat d'ingestion synchrone
 * ({@link IngestionService#ingest} / {@link IngestionService#ingestLocalFiles}).
 *
 * <p>Deux régressions distinctes ont déjà cassé {@code main} sur ce chemin ; ce test
 * verrouille explicitement les deux :</p>
 *
 * <ol>
 *   <li><b>Délégation à l'exécuteur réel.</b> {@code ingest()} délègue à
 *       {@code executor.ingestOne(...)}. Un test qui <i>mocke</i> l'exécuteur ne voit pas
 *       qu'un {@code IngestOneResult} nul ferait échouer le chemin — d'où un exécuteur RÉEL ici,
 *       seules les frontières réseau (embedding, ChromaDB, FTS) étant simulées. On vérifie que le
 *       document est bien indexé <i>et enregistré</i> (chunks &gt; 0 → {@code recordIngestion}).</li>
 *   <li><b>Enregistrement d'un document partiellement indexé.</b> Le critère d'enregistrement est
 *       {@code chunks &gt; 0} — et NON {@code chunks &gt; 0 && complete}. Un lot partiellement
 *       indexé (au moins un chunk) DOIT être enregistré, sinon la dédup ne peut jamais réparer un
 *       document à moitié ingéré. Vérifié via un exécuteur simulé renvoyant {@code complete=false}.</li>
 * </ol>
 */
class IngestionServiceContractTest {

    @TempDir
    Path tempDir;

    private EmbeddingService embeddingService;
    private ChromaDbClient chromaDbClient;
    private FtsService ftsService;
    private SpectraProperties props;
    private IngestedFileRepository repository;
    private GedService gedService;
    private DocumentExtractorFactory factory;
    private TextCleanerService textCleaner;
    private ChunkingService chunkingService;

    @BeforeEach
    void setUp() {
        // Extracteur réel pour les .txt ; le reste du pipeline (nettoyage, chunking) est réel aussi.
        factory = new DocumentExtractorFactory(List.of(new TxtExtractor()));
        textCleaner = new TextCleanerService();
        chunkingService = new ChunkingService();

        embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> List.of(0.1f, 0.2f, 0.3f)).toList();
        });

        chromaDbClient = mock(ChromaDbClient.class);
        when(chromaDbClient.getOrCreateCollection(anyString())).thenReturn("test-collection-id");

        SpectraProperties.PipelineProperties pipeline =
                new SpectraProperties.PipelineProperties(512, 64, 10, 30, 120, 4);
        props = mock(SpectraProperties.class);
        when(props.pipeline()).thenReturn(pipeline);

        ftsService = mock(FtsService.class);
        repository = mock(IngestedFileRepository.class);       // existsById → false par défaut
        gedService = mock(GedService.class);
    }

    /** Construit l'IngestionService avec l'exécuteur fourni (réel ou simulé). */
    private IngestionService serviceWith(IngestionTaskExecutor executor) {
        return new IngestionService(
                factory, textCleaner, chunkingService, embeddingService, chromaDbClient,
                ftsService, executor, repository, gedService,
                mock(StreamSourceRepository.class), props, 50, 4);
    }

    private IngestionTaskExecutor realExecutor() {
        return new IngestionTaskExecutor(
                factory, textCleaner, chunkingService, embeddingService, chromaDbClient,
                ftsService, new SimpleMeterRegistry(), props, 10, 50, 4);
    }

    // ── 1. Délégation à l'exécuteur réel : ingest() indexe ET enregistre ────────

    @Test
    void ingest_withRealExecutor_indexesAndRecordsDocument() throws Exception {
        IngestionService service = serviceWith(realExecutor());

        String text = "Spectra est une plateforme RAG. "
                + "Elle indexe des documents pour permettre de les retrouver plus tard.";
        int chunks = service.ingest("doc.txt", new ByteArrayInputStream(text.getBytes()), "test-collection-id");

        assertThat(chunks).isGreaterThanOrEqualTo(1);
        // « enregistré » = persistance + audit initial (chemin recordIngestion, hash inédit).
        verify(repository).save(any(IngestedFileEntity.class));
        verify(gedService).audit(anyString(), any(), anyString(), any());
    }

    @Test
    void ingestLocalFiles_withRealExecutor_indexesAndRecordsDocument() throws Exception {
        IngestionService service = serviceWith(realExecutor());

        Path file = tempDir.resolve("local.txt");
        Files.writeString(file, "Premier document local à indexer par le batch. "
                + "Il contient assez de texte pour produire au moins un chunk.");

        int total = service.ingestLocalFiles(List.of(file));

        assertThat(total).isGreaterThanOrEqualTo(1);
        verify(repository).save(any(IngestedFileEntity.class));
    }

    // ── 2. Un document partiellement indexé (complete=false) est enregistré ─────

    @Test
    void ingest_partialIndex_stillRecordsDocument() throws Exception {
        // L'exécuteur renvoie 3 chunks mais complete=false (indexation partielle).
        IngestionTaskExecutor executor = mock(IngestionTaskExecutor.class);
        when(executor.ingestOne(anyString(), any(), any(), any(), any()))
                .thenReturn(new IngestionTaskExecutor.IngestOneResult(3, "txt", 0, false));

        IngestionService service = serviceWith(executor);
        int chunks = service.ingest("partial.txt",
                new ByteArrayInputStream("contenu partiel".getBytes()), "test-collection-id");

        // Le critère d'enregistrement est chunks>0, indépendamment de complete.
        assertThat(chunks).isEqualTo(3);
        verify(repository).save(any(IngestedFileEntity.class));
    }

    @Test
    void ingest_zeroChunks_doesNotRecord() throws Exception {
        // Symétrie : aucun chunk indexé → rien n'est enregistré (pas de faux positif GED).
        IngestionTaskExecutor executor = mock(IngestionTaskExecutor.class);
        when(executor.ingestOne(anyString(), any(), any(), any(), any()))
                .thenReturn(new IngestionTaskExecutor.IngestOneResult(0, "txt", 0, true));

        IngestionService service = serviceWith(executor);
        int chunks = service.ingest("empty.txt",
                new ByteArrayInputStream("".getBytes()), "test-collection-id");

        assertThat(chunks).isZero();
        verify(repository, never()).save(any(IngestedFileEntity.class));
    }
}
