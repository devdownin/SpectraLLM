package fr.spectra.service;

import fr.spectra.persistence.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.NoSuchElementException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentPreviewTest {
    private final IngestedFileRepository files = mock(IngestedFileRepository.class);
    private final ChromaDbClient chroma = mock(ChromaDbClient.class);
    private final GedService service = new GedService(files, mock(DocumentModelLinkRepository.class),
            mock(AuditLogRepository.class), chroma, mock(FtsService.class), "/tmp/preview-test");

    private void document(String collection) {
        IngestedFileEntity doc = new IngestedFileEntity("sha", "same-name.pdf", "PDF",
                java.time.Instant.now(), 5, collection, 0.9);
        when(files.findById("sha")).thenReturn(Optional.of(doc));
        when(chroma.resolveCollectionIdUnchecked("docs")).thenReturn("collection-id");
    }

    @Test void usesShaIdentityAndPreservesText() {
        document("docs");
        when(chroma.getDocumentTextsByMetadata("collection-id", "sha256", "sha", 13))
                .thenReturn(List.of("Extract one", "Extract two"));
        assertThat(service.preview("sha")).isEqualTo(new GedService.DocumentPreview(List.of("Extract one", "Extract two"), false));
        verify(chroma, never()).getDocumentTextsByMetadata(anyString(), eq("sourceFile"), anyString(), anyInt());
    }
    @Test void boundsChunkCountAndCharacters() {
        document("docs");
        when(chroma.getDocumentTextsByMetadata("collection-id", "sha256", "sha", 13))
                .thenReturn(java.util.Collections.nCopies(13, "x".repeat(3000)));
        var result = service.preview("sha");
        assertThat(result.truncated()).isTrue();
        assertThat(result.chunks()).hasSize(12).allMatch(s -> s.length() == 2000);
    }
    @Test void missingIndexIdentityDoesNotReadAnArbitraryCollection() {
        document(null);
        assertThat(service.preview("sha").chunks()).isEmpty();
        verify(chroma, never()).getDocumentTextsByMetadata(anyString(), anyString(), anyString(), anyInt());
    }
    @Test void missingDocumentFails() {
        assertThatThrownBy(() -> service.preview("unknown")).isInstanceOf(NoSuchElementException.class);
    }
    @Test void indexFailureIsNotReportedAsAnEmptyIndex() {
        document("docs");
        when(chroma.getDocumentTextsByMetadata("collection-id", "sha256", "sha", 13))
                .thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> service.preview("sha")).isInstanceOf(IllegalStateException.class);
    }
    @Test void noChunksIsAnExplicitEmptyPreview() {
        document("docs");
        when(chroma.getDocumentTextsByMetadata("collection-id", "sha256", "sha", 13)).thenReturn(List.of());
        assertThat(service.preview("sha")).isEqualTo(new GedService.DocumentPreview(List.of(), false));
    }
}
