package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.IngestionTask;
import fr.spectra.model.TextChunk;
import fr.spectra.persistence.*;
import fr.spectra.service.extraction.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IngestionCompletionRegressionTest {
    @TempDir Path archive;

    @Test void partialUploadIsVisibleAndNormalRetryReplacesChunksWithoutDuplicates() {
        var rows = new HashMap<String, IngestedFileEntity>();
        var vectors = new HashMap<String, TextChunk>();
        var repo = IngestedFileRepositoryFixture.backedBy(rows);
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.pipeline()).thenReturn(new SpectraProperties.PipelineProperties(512, 64, 1, 30, 120, 4));
        var factory = new DocumentExtractorFactory(List.of(new TxtExtractor()));
        var cleaner = new TextCleanerService();
        var chunker = new ChunkingService(props);
        var chroma = mock(ChromaDbClient.class);
        when(chroma.getOrCreateCollection(anyString())).thenReturn("col");
        doAnswer(i -> {
            for (TextChunk chunk : i.<List<TextChunk>>getArgument(1)) vectors.put(chunk.id(), chunk);
            return null;
        }).when(chroma).addDocuments(anyString(), anyList(), anyList());
        when(chroma.deleteByMetadata(anyString(), eq("sha256"), anyString())).thenAnswer(i -> {
            int before = vectors.size();
            vectors.values().removeIf(chunk -> i.getArgument(2).equals(chunk.metadata().get("sha256")));
            return before - vectors.size();
        });
        var fts = new FtsService(chroma, props);
        var ged = new GedService(repo, mock(DocumentModelLinkRepository.class), mock(AuditLogRepository.class),
                chroma, fts, archive.toString());
        var embed = mock(EmbeddingService.class);
        AtomicInteger calls = new AtomicInteger();
        when(embed.embedBatch(anyList())).thenAnswer(i -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("panne au deuxième lot");
            return i.<List<String>>getArgument(0).stream().map(t -> List.of(0.1f, 0.2f)).toList();
        });
        var executor = new IngestionTaskExecutor(factory, cleaner, chunker, embed, chroma, fts,
                new SimpleMeterRegistry(), props, 1, 50, 1);
        var service = new IngestionService(factory, cleaner, chunker, embed, chroma, fts, executor,
                repo, ged, mock(StreamSourceRepository.class), mock(IngestionTaskRepository.class), props, 50, 1, 0);
        byte[] content = "Une procédure de sécurité clairement expliquée et vérifiable. ".repeat(1200).getBytes(java.nio.charset.StandardCharsets.UTF_8);

        String first = service.submit(List.of(new MockMultipartFile("files", "rapport.txt", "text/plain", content)));
        assertThat(service.getTask(first).status()).isEqualTo(IngestionTask.Status.COMPLETED);
        assertThat(service.getTask(first).fileErrors()).anyMatch(error -> error.contains("deuxième lot"));
        assertThat(rows.values()).singleElement().satisfies(doc -> assertThat(doc.isIngestionComplete()).isFalse());
        assertThat(vectors).hasSize(1);

        String retry = service.submit(List.of(new MockMultipartFile("files", "rapport.txt", "text/plain", content)));
        assertThat(service.getTask(retry).fileErrors()).isEmpty();
        assertThat(rows.values()).singleElement().satisfies(doc -> {
            assertThat(doc.isIngestionComplete()).isTrue();
            assertThat(doc.getChunksCreated()).isEqualTo(vectors.size()).isGreaterThan(1);
        });
        assertThat(fts.indexedCount("spectra_documents")).isEqualTo(vectors.size());
        int size = vectors.size();
        service.submit(List.of(new MockMultipartFile("files", "rapport.txt", "text/plain", content)));
        assertThat(vectors).hasSize(size);
    }
}
