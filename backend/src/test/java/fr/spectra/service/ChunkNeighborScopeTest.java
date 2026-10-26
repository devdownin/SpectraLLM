package fr.spectra.service;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ChunkNeighborScopeTest {
    @Test
    void entriesWithSameArchiveAndPositionHaveDifferentScopes() {
        var first = ChunkNeighborScope.from(Map.of("sha256", "archive", "sourceFile", "archive.zip",
                "zipEntry", "archive.zip/a.txt"), false).orElseThrow();
        var second = ChunkNeighborScope.from(Map.of("sha256", "archive", "sourceFile", "archive.zip",
                "zipEntry", "archive.zip/b.txt"), false).orElseThrow();
        assertThat(first.identityValue()).isEqualTo(second.identityValue());
        assertThat(first.zipEntry()).isNotEqualTo(second.zipEntry());
    }

    @Test
    void legacyArchiveIsNotExpandedWithoutSubdocumentIdentity() {
        assertThat(ChunkNeighborScope.from(Map.of("sha256", "archive", "sourceFile", "ARCHIVE.ZIP"), false)).isEmpty();
        assertThat(ChunkNeighborScope.from(Map.of("sourceFile", "archive.zip"), false)).isEmpty();
        assertThat(ChunkNeighborScope.from(Map.of("sourceFile", "ordinary.txt", "zipEntry", " "), false)).isEmpty();
    }

    @Test
    void ordinaryDocumentsRetainTheirIdentityRules() {
        assertThat(ChunkNeighborScope.from(Map.of("sha256", "document", "sourceFile", "ordinary.pdf"), true))
                .contains(new ChunkNeighborScope("sha256", "document", null));
        assertThat(ChunkNeighborScope.from(Map.of("sourceFile", "ordinary.pdf"), false))
                .contains(new ChunkNeighborScope("sourceFile", "ordinary.pdf", null));
        assertThat(ChunkNeighborScope.from(Map.of("sourceFile", "ordinary.pdf"), true)).isEmpty();
    }
}
