package fr.spectra.sources;

import fr.spectra.persistence.StreamSourceEntity;
import fr.spectra.persistence.StreamSourceRepository;
import fr.spectra.service.ChromaDbClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SourcePreviewControllerTest {
    private final SourceSyncService sources = mock(SourceSyncService.class);
    private final StreamSourceRepository publications = mock(StreamSourceRepository.class);
    private final ChromaDbClient chroma = mock(ChromaDbClient.class);
    private final SourcePreviewController controller = new SourcePreviewController(sources, publications, chroma);
    private void source() {
        when(sources.get("instance")).thenReturn(new SourceModels.Source("instance", "Knowledge", SourceModels.Type.LOCAL_FOLDER,
                "knowledge_collection", true, 0, null, SourceModels.Status.SUCCESS, 1, null, null, null, null));
    }
    @Test void previewUsesPublishedHashAndBoundsPassagesBeforeReturningText() {
        source();
        var pointer = new StreamSourceEntity("source://instance/document", "knowledge_collection");
        pointer.setContentHash("published-revision");
        when(publications.findById("source://instance/document")).thenReturn(Optional.of(pointer));
        when(chroma.resolveCollectionIdUnchecked("knowledge_collection")).thenReturn("collection-id");
        when(chroma.getDocumentTextsByMetadata("collection-id", "sha256", "published-revision", 13))
                .thenReturn(IntStream.range(0,13).mapToObj(i -> "x".repeat(2100)).toList());
        var preview = controller.preview("instance", "document");
        assertThat(preview.truncated()).isTrue();
        assertThat(preview.chunks()).hasSize(12).allSatisfy(text -> assertThat(text).hasSize(2000));
        verify(chroma, never()).getDocumentTextsByMetadata(anyString(), eq("sourceFile"), anyString(), anyInt());
    }
    @Test void deletionIntentOrUnpublishedDocumentReturnsNoStagedText() {
        source();
        when(publications.findById("source://instance/document")).thenReturn(Optional.of(new StreamSourceEntity("source://instance/document", "knowledge_collection")));
        assertThat(controller.preview("instance", "document").chunks()).isEmpty();
        verifyNoInteractions(chroma);
    }
    @Test void scopeDenialHappensBeforeReadingAnyPublicationOrVectorData() {
        source();
        when(sources.document("instance", "foreign-document")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> controller.preview("instance", "foreign-document")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(publications, chroma);
    }
    @Test void shortPublishedDocumentDoesNotClaimTruncation() {
        source();
        var pointer = new StreamSourceEntity("source://instance/document", "knowledge_collection"); pointer.setContentHash("hash");
        when(publications.findById("source://instance/document")).thenReturn(Optional.of(pointer));
        when(chroma.resolveCollectionIdUnchecked(anyString())).thenReturn("collection-id");
        when(chroma.getDocumentTextsByMetadata("collection-id", "sha256", "hash", 13)).thenReturn(List.of("Published passage"));
        assertThat(controller.preview("instance", "document")).isEqualTo(new SourcePreviewController.Preview(List.of("Published passage"), false));
    }
}
