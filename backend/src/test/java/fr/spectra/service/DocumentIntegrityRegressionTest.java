package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.model.TextChunk;
import fr.spectra.persistence.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentIntegrityRegressionTest {
    @TempDir Path archive;
    private final Map<String, IngestedFileEntity> rows = new HashMap<>();
    private IngestedFileRepository repo;
    private ChromaDbClient chroma;
    private FtsService fts;
    private GedService ged;

    @BeforeEach void setUp() {
        repo = IngestedFileRepositoryFixture.backedBy(rows);
        chroma = mock(ChromaDbClient.class);
        when(chroma.getOrCreateCollection(anyString())).thenAnswer(i -> i.getArgument(0));
        fts = new FtsService(chroma, mock(SpectraProperties.class));
        ged = newGed();
    }

    private GedService newGed() {
        return new GedService(repo, mock(DocumentModelLinkRepository.class), mock(AuditLogRepository.class),
                chroma, fts, archive.toString());
    }

    private void add(String sha, String collection) {
        rows.put(sha, new IngestedFileEntity(sha, "rapport.pdf", "PDF", Instant.now(), 1, collection, 0.8));
        fts.indexChunks(List.of(new TextChunk(sha + "-chunk", "preuve " + sha, 0,
                "rapport.pdf", Map.of("sha256", sha))), collection);
    }

    @Test void deletionKeepsDurableRequestDuringOutageAndResumesWithNewService() {
        add("shaA", "col-a");
        when(chroma.deleteByMetadata("col-a", "sha256", "shaA"))
                .thenThrow(new IllegalStateException("ChromaDB hors ligne")).thenReturn(1);
        assertThat(ged.deleteDocument("shaA", "alice")).containsEntry("deletionPending", true);
        assertThat(rows.get("shaA").isDeletionPending()).isTrue();
        assertThat(rows.get("shaA").getDeletionActor()).isEqualTo("alice");
        verify(repo).saveAndFlush(rows.get("shaA"));
        verify(repo, never()).delete(any(IngestedFileEntity.class));

        newGed().retryPendingDeletions();
        assertThat(rows).doesNotContainKey("shaA");
        assertThat(fts.indexedCount("col-a")).isZero();
    }

    @Test void deletingOneHomonymPreservesTheOtherInLexicalSearch() {
        add("shaA", "col-a"); add("shaB", "col-a");
        when(chroma.deleteByMetadata("col-a", "sha256", "shaA")).thenReturn(1);
        assertThat(ged.deleteDocument("shaA", "alice")).containsEntry("deletionPending", false);
        assertThat(rows).containsKey("shaB");
        assertThat(fts.search("shaB", "col-a", 10)).hasSize(1);
        assertThat(fts.indexedCount("col-a")).isEqualTo(1);
        verify(chroma, never()).deleteBySource(anyString(), anyString());
    }

    @Test void missingTargetVectorsDoNotTriggerBroadSourceDeletion() {
        add("shaA", "col-a"); add("shaB", "col-a");
        when(chroma.deleteByMetadata("col-a", "sha256", "shaA")).thenReturn(0);
        ged.deleteDocument("shaA", "alice");
        assertThat(fts.search("shaB", "col-a", 10)).hasSize(1);
        verify(chroma, never()).deleteBySource(anyString(), anyString());
    }

    @Test void sourceDeletionIsScopedToRequestedCollection() {
        add("shaA", "col-a"); add("shaB", "col-b");
        assertThat(ged.deleteBySourceFile("rapport.pdf", "col-a", "alice"))
                .containsEntry("documentsDeleted", 1);
        assertThat(rows).doesNotContainKey("shaA").containsKey("shaB");
        assertThat(fts.indexedCount("col-b")).isEqualTo(1);
        verify(chroma, never()).deleteByMetadata("col-b", "sha256", "shaB");
    }

    @Test void ambiguousLegacyHomonymsAreKeptPendingInsteadOfDeletingUnrelatedChunks() {
        add("shaA", "col-a"); add("shaB", "col-a");
        when(chroma.getLegacyChunkIdsBySource("col-a", "rapport.pdf")).thenReturn(List.of("legacy"));
        assertThat(ged.deleteDocument("shaA", "alice")).containsEntry("deletionPending", true);
        assertThat(rows).containsKeys("shaA", "shaB");
        verify(chroma, never()).deleteChunksByIds(anyString(), anyList());
    }

    @Test void lexicalCleanupFailureDoesNotDeleteTheGedRow() {
        add("shaA", "col-a");
        FtsService failing = mock(FtsService.class);
        doThrow(new IllegalStateException("FTS indisponible")).when(failing).removeByDocument("shaA", "col-a");
        GedService service = new GedService(repo, mock(DocumentModelLinkRepository.class),
                mock(AuditLogRepository.class), chroma, failing, archive.toString());
        assertThat(service.deleteDocument("shaA", "alice")).containsEntry("deletionPending", true);
        assertThat(rows).containsKey("shaA");
    }

    @Test void documentIdentityCannotEscapeTheArchiveDirectory() throws Exception {
        Path external = java.nio.file.Files.createTempDirectory(archive.getParent(), "external-archive-");
        Path manifest = external.resolve("manifest.json");
        try {
            java.nio.file.Files.writeString(manifest, "preuve à conserver");
            String maliciousSha = external.toAbsolutePath().toString();
            add(maliciousSha, "col-a");
            assertThat(ged.deleteDocument(maliciousSha, "alice"))
                    .containsEntry("deletionPending", true);
            assertThat(java.nio.file.Files.readString(manifest)).isEqualTo("preuve à conserver");
            assertThat(rows).containsKey(maliciousSha);
            verify(chroma, never()).deleteByMetadata(anyString(), anyString(), anyString());
        } finally {
            java.nio.file.Files.deleteIfExists(manifest);
            java.nio.file.Files.deleteIfExists(external);
        }
    }

}
