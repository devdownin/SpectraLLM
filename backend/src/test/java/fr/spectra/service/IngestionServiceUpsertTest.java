package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.model.ExtractedDocument;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.persistence.StreamSourceEntity;
import fr.spectra.persistence.StreamSourceRepository;
import fr.spectra.service.extraction.DocumentExtractor;
import fr.spectra.service.extraction.DocumentExtractorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests de l'upsert streaming (Kafka → RAG au fil de l'eau).
 * Utilise un faux {@link StreamSourceRepository} en mémoire pour valider l'idempotence
 * de bout en bout, et des mocks pour ChromaDB / BM25 / embeddings.
 */
class IngestionServiceUpsertTest {

    private static final String COLLECTION = "spectra_stream";

    private IngestionService service;
    private ChromaDbClient chromaDb;
    private FtsService fts;
    private EmbeddingService embeddingService;
    private final Map<String, StreamSourceEntity> store = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        DocumentExtractorFactory factory = mock(DocumentExtractorFactory.class);
        DocumentExtractor extractor = mock(DocumentExtractor.class);
        when(factory.resolveContentType(anyString())).thenReturn("application/json");
        when(factory.getExtractor(anyString())).thenReturn(extractor);
        // L'extracteur renvoie le payload brut comme texte.
        when(extractor.extract(anyString(), any(InputStream.class))).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            InputStream in = inv.getArgument(1);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new ExtractedDocument(name, "application/json", text, 1, Map.of());
        });

        embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> List.of(0.1f, 0.2f, 0.3f)).toList();
        });

        chromaDb = mock(ChromaDbClient.class);
        when(chromaDb.getOrCreateCollection(anyString())).thenReturn("cid");
        when(chromaDb.deleteBySource(anyString(), anyString())).thenReturn(1);

        fts = mock(FtsService.class);

        StreamSourceRepository streamRepo = mock(StreamSourceRepository.class);
        when(streamRepo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        when(streamRepo.save(any(StreamSourceEntity.class))).thenAnswer(inv -> {
            StreamSourceEntity e = inv.getArgument(0);
            store.put(e.getSourceKey(), e);
            return e;
        });
        doAnswer(inv -> { store.remove(inv.getArgument(0)); return null; })
                .when(streamRepo).deleteById(anyString());

        SpectraProperties.PipelineProperties pipeline = new SpectraProperties.PipelineProperties(512, 64, 10, 30, 120, 4);
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.pipeline()).thenReturn(pipeline);

        service = new IngestionService(
                factory, new TextCleanerService(), new ChunkingService(),
                embeddingService, chromaDb, fts,
                mock(IngestionTaskExecutor.class),
                mock(IngestedFileRepository.class),
                mock(GedService.class),
                streamRepo, props, 50, 4, 0);
    }

    private IngestionService.UpsertResult upsert(String key, String payload) throws Exception {
        InputStream body = new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8));
        return service.upsertFromStream(key, key + ".json", body, COLLECTION, "topic/0/0");
    }

    @Test
    void newKey_indexesChunks() throws Exception {
        IngestionService.UpsertResult r = upsert("kafka://t/dossier-1", "Contenu initial du dossier.");
        assertThat(r.kind()).isEqualTo(IngestionService.UpsertResult.Kind.UPSERTED);
        assertThat(r.indexedChunks()).isGreaterThan(0);
        verify(chromaDb, atLeastOnce()).addDocuments(anyString(), anyList(), anyList());
        verify(fts, atLeastOnce()).indexChunks(anyList(), eq(COLLECTION));
        assertThat(store).containsKey("kafka://t/dossier-1");
        assertThat(store.get("kafka://t/dossier-1").getVersion()).isEqualTo(1L);
    }

    @Test
    void extraMetadataAndFreshnessArePropagatedToChunks() throws Exception {
        InputStream body = new ByteArrayInputStream("Statut courant.".getBytes(StandardCharsets.UTF_8));
        service.upsertFromStream("kafka://t/dossier-5", "kafka://t/dossier-5.json", body,
                COLLECTION, "t/0/0", Map.of("statut", "clos"));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<fr.spectra.model.TextChunk>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(chromaDb, atLeastOnce()).addDocuments(anyString(), captor.capture(), anyList());
        Map<String, String> meta = captor.getValue().get(0).metadata();
        assertThat(meta).containsEntry("statut", "clos");   // champ mappé propagé
        assertThat(meta).containsKey("ingestedAt");          // fraîcheur temporelle injectée
        assertThat(meta).containsEntry("sourceFile", "kafka://t/dossier-5");
    }

    @Test
    void changedContent_purgesOldThenReindexes() throws Exception {
        upsert("kafka://t/dossier-2", "Statut : ouvert.");
        IngestionService.UpsertResult r = upsert("kafka://t/dossier-2", "Statut : clos.");
        assertThat(r.kind()).isEqualTo(IngestionService.UpsertResult.Kind.UPSERTED);
        // La version changée déclenche une purge des deux index avant réindexation.
        verify(chromaDb, atLeastOnce()).deleteBySource("cid", "kafka://t/dossier-2");
        verify(fts, atLeastOnce()).removeBySource("kafka://t/dossier-2", COLLECTION);
        assertThat(store.get("kafka://t/dossier-2").getVersion()).isEqualTo(2L);
    }

    @Test
    void unchangedContent_isNoOp() throws Exception {
        upsert("kafka://t/dossier-3", "Contenu stable.");
        IngestionService.UpsertResult r = upsert("kafka://t/dossier-3", "Contenu stable.");
        assertThat(r.kind()).isEqualTo(IngestionService.UpsertResult.Kind.UNCHANGED);
        // Une seule indexation au total : le rejeu identique ne réindexe pas.
        verify(chromaDb, times(1)).addDocuments(anyString(), anyList(), anyList());
        assertThat(store.get("kafka://t/dossier-3").getVersion()).isEqualTo(1L);
    }

    @Test
    void tombstone_deletesSource() throws Exception {
        upsert("kafka://t/dossier-4", "À supprimer.");
        IngestionService.UpsertResult r = upsert("kafka://t/dossier-4", ""); // value vide = tombstone
        assertThat(r.kind()).isEqualTo(IngestionService.UpsertResult.Kind.DELETED);
        verify(chromaDb, atLeastOnce()).deleteBySource("cid", "kafka://t/dossier-4");
        verify(fts, atLeastOnce()).removeBySource("kafka://t/dossier-4", COLLECTION);
        assertThat(store).doesNotContainKey("kafka://t/dossier-4");
    }
}
