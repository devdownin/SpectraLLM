package fr.spectra.mcp;

import fr.spectra.config.SpectraProperties;
import fr.spectra.controller.StatusController;
import fr.spectra.dto.QueryRequest;
import fr.spectra.dto.QueryResponse;
import fr.spectra.service.ChromaDbClient;
import fr.spectra.service.GedService;
import fr.spectra.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpectraMcpToolsTest {

    private RagService ragService;
    private ChromaDbClient chroma;
    private GedService ged;
    private SpectraMcpTools tools;

    @BeforeEach
    void setUp() {
        ragService = mock(RagService.class);
        chroma = mock(ChromaDbClient.class);
        ged = mock(GedService.class);
        SpectraProperties properties = mock(SpectraProperties.class);
        when(properties.chromadb()).thenReturn(new SpectraProperties.ChromaDbProperties("http://chroma", "docs"));
        when(chroma.listCollections()).thenReturn(List.of(Map.of("name", "docs")));
        tools = new SpectraMcpTools(ragService, chroma, ged, mock(StatusController.class), properties);
    }

    private static RagService.RagContext context(List<QueryResponse.Source> sources) {
        return new RagService.RagContext(List.of(), List.of(), List.of(), null, null, sources, null,
                true, false, false, false, false);
    }

    @Test
    void searchReturnsRankedPassagesWithoutCallingTheModel() {
        when(ragService.retrieveContext(any(QueryRequest.class))).thenReturn(context(List.of(
                new QueryResponse.Source("premier", "a.pdf", 0.1, 0.9f, null),
                new QueryResponse.Source("second", "b.pdf", 0.2))));

        SpectraMcpTools.SearchResult result = tools.search("q", null, null);

        assertThat(result.collection()).isEqualTo("docs");
        assertThat(result.rerankApplied()).isTrue();
        assertThat(result.note()).isNull();
        assertThat(result.passages()).extracting(SpectraMcpTools.Passage::rank, SpectraMcpTools.Passage::sourceFile)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "a.pdf"), org.assertj.core.groups.Tuple.tuple(2, "b.pdf"));
        verify(ragService, never()).query(any(QueryRequest.class));
    }

    @Test
    void anEmptySearchSaysNothingMatchedRatherThanReturningABareList() {
        when(ragService.retrieveContext(any(QueryRequest.class))).thenReturn(context(List.of()));

        assertThat(tools.search("q", "docs", 3).note()).contains("No passage matched in collection 'docs'");
    }

    @Test
    void aLongPassageIsCutAndSaysSo() {
        String longText = "x".repeat(SpectraMcpTools.MAX_PASSAGE_CHARS + 10);
        when(ragService.retrieveContext(any(QueryRequest.class))).thenReturn(
                context(List.of(new QueryResponse.Source(longText, "a.pdf", 0.1))));

        SpectraMcpTools.Passage passage = tools.search("q", null, null).passages().getFirst();

        assertThat(passage.textTruncated()).isTrue();
        assertThat(passage.text()).hasSize(SpectraMcpTools.MAX_PASSAGE_CHARS);
    }

    @Test
    void thePassageCountIsClampedToTheBoundTheRetrievalAccepts() {
        when(ragService.retrieveContext(any(QueryRequest.class))).thenReturn(context(List.of()));
        ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);

        tools.search("q", null, 500);

        verify(ragService).retrieveContext(request.capture());
        assertThat(request.getValue().maxContextChunks()).isEqualTo(SpectraMcpTools.MAX_PASSAGES);
    }

    @Test
    void anUnknownCollectionIsRefusedRatherThanCreatedAndSearchedEmpty() {
        assertThatThrownBy(() -> tools.search("q", "doc", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collection 'doc' does not exist");
        verify(ragService, never()).retrieveContext(any(QueryRequest.class));
        verify(chroma, never()).getOrCreateCollection(anyString());
    }

    @Test
    void listingAnUnknownCollectionReportsItMissingWithoutCreatingIt() {
        SpectraMcpTools.DocumentList list = tools.listDocuments("doc");

        assertThat(list.collectionExists()).isFalse();
        assertThat(list.chunksBySource()).isEmpty();
        verify(chroma, never()).getOrCreateCollection(anyString());
    }

    @Test
    void listingCountsSourcesAndChunks() {
        when(chroma.getOrCreateCollection("docs")).thenReturn("id-1");
        when(chroma.listSources("id-1")).thenReturn(Map.of("a.pdf", 3, "b.pdf", 4));

        SpectraMcpTools.DocumentList list = tools.listDocuments(null);

        assertThat(list.collectionExists()).isTrue();
        assertThat(list.sourceCount()).isEqualTo(2);
        assertThat(list.chunkCount()).isEqualTo(7);
    }

    @Test
    void askReturnsTheAnswerWithTheSourcesItWasGroundedOn() {
        when(ragService.query(any(QueryRequest.class))).thenReturn(new QueryResponse("réponse",
                List.of(new QueryResponse.Source("passage", "a.pdf", 0.1)), 42L, false, true));

        SpectraMcpTools.AskResult result = tools.ask("q", null, null);

        assertThat(result.answer()).isEqualTo("réponse");
        assertThat(result.sources()).singleElement().extracting(SpectraMcpTools.Passage::sourceFile).isEqualTo("a.pdf");
        assertThat(result.hybridSearchApplied()).isTrue();
        assertThat(result.durationMs()).isEqualTo(42L);
    }

    @Test
    void aBlankQuestionIsRefused() {
        assertThatThrownBy(() -> tools.ask(" ", null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void previewPassesTheRegistryAnswerThrough() {
        when(ged.preview("abc")).thenReturn(new GedService.DocumentPreview(List.of("c1"), true));

        SpectraMcpTools.DocumentPreview preview = tools.previewDocument("abc");

        assertThat(preview.chunks()).containsExactly("c1");
        assertThat(preview.truncated()).isTrue();
    }
}
