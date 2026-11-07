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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpectraMcpToolsTest {

    private RagService ragService;
    private ChromaDbClient chroma;
    private GedService ged;
    private McpCallJournal journal;
    private SpectraMcpTools tools;
    private final io.modelcontextprotocol.server.McpSyncServerExchange exchange =
            mock(io.modelcontextprotocol.server.McpSyncServerExchange.class);

    @BeforeEach
    void setUp() {
        ragService = mock(RagService.class);
        chroma = mock(ChromaDbClient.class);
        ged = mock(GedService.class);
        SpectraProperties properties = mock(SpectraProperties.class);
        when(properties.chromadb()).thenReturn(new SpectraProperties.ChromaDbProperties("http://chroma", "docs"));
        when(chroma.listCollections()).thenReturn(List.of(Map.of("name", "docs")));
        journal = new McpCallJournal(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), new McpRateLimiter(0, 1));
        when(exchange.transportContext()).thenReturn(McpCaller.LEGACY.context());
        tools = new SpectraMcpTools(ragService, chroma, ged, mock(StatusController.class), journal, properties);
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

        SpectraMcpTools.SearchResult result = tools.search("q", null, null, exchange);

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

        assertThat(tools.search("q", "docs", 3, exchange).note()).contains("No passage matched in collection 'docs'");
    }

    @Test
    void aLongPassageIsCutAndSaysSo() {
        String longText = "x".repeat(SpectraMcpTools.MAX_PASSAGE_CHARS + 10);
        when(ragService.retrieveContext(any(QueryRequest.class))).thenReturn(
                context(List.of(new QueryResponse.Source(longText, "a.pdf", 0.1))));

        SpectraMcpTools.Passage passage = tools.search("q", null, null, exchange).passages().getFirst();

        assertThat(passage.textTruncated()).isTrue();
        assertThat(passage.text()).hasSize(SpectraMcpTools.MAX_PASSAGE_CHARS);
    }

    @Test
    void thePassageCountIsClampedToTheBoundTheRetrievalAccepts() {
        when(ragService.retrieveContext(any(QueryRequest.class))).thenReturn(context(List.of()));
        ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);

        tools.search("q", null, 500, exchange);

        verify(ragService).retrieveContext(request.capture());
        assertThat(request.getValue().maxContextChunks()).isEqualTo(SpectraMcpTools.MAX_PASSAGES);
    }

    @Test
    void anUnknownCollectionIsRefusedRatherThanCreatedAndSearchedEmpty() {
        assertThatThrownBy(() -> tools.search("q", "doc", null, exchange))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collection 'doc' does not exist");
        verify(ragService, never()).retrieveContext(any(QueryRequest.class));
        verify(chroma, never()).getOrCreateCollection(anyString());
    }

    @Test
    void listingAnUnknownCollectionReportsItMissingWithoutCreatingIt() {
        SpectraMcpTools.DocumentList list = tools.listDocuments("doc", exchange);

        assertThat(list.collectionExists()).isFalse();
        assertThat(list.chunksBySource()).isEmpty();
        verify(chroma, never()).getOrCreateCollection(anyString());
    }

    @Test
    void listingCountsSourcesAndChunks() {
        when(chroma.getOrCreateCollection("docs")).thenReturn("id-1");
        when(chroma.listSources("id-1")).thenReturn(Map.of("a.pdf", 3, "b.pdf", 4));

        SpectraMcpTools.DocumentList list = tools.listDocuments(null, exchange);

        assertThat(list.collectionExists()).isTrue();
        assertThat(list.sourceCount()).isEqualTo(2);
        assertThat(list.chunkCount()).isEqualTo(7);
    }

    @Test
    void askReturnsTheAnswerWithTheSourcesItWasGroundedOn() {
        when(ragService.query(any(QueryRequest.class))).thenReturn(new QueryResponse("réponse",
                List.of(new QueryResponse.Source("passage", "a.pdf", 0.1)), 42L, false, true));

        SpectraMcpTools.AskResult result = tools.ask("q", null, null, exchange);

        assertThat(result.answer()).isEqualTo("réponse");
        assertThat(result.sources()).singleElement().extracting(SpectraMcpTools.Passage::sourceFile).isEqualTo("a.pdf");
        assertThat(result.hybridSearchApplied()).isTrue();
        assertThat(result.durationMs()).isEqualTo(42L);
    }

    @Test
    void aVectorStoreThatDoesNotAnswerIsNamedRatherThanEchoedRaw() {
        when(chroma.listCollections()).thenThrow(McpToolFailureTest.refused("http://chromadb:8000/api/v2/collections"));

        assertThatThrownBy(() -> tools.listDocuments("docs", exchange))
                .isInstanceOf(McpToolFailure.class)
                .hasMessageContaining("http://chromadb:8000 did not answer (connection refused)");
    }

    @Test
    void everyCallIsJournaledWithItsOutcome() {
        when(ragService.retrieveContext(any(QueryRequest.class))).thenReturn(context(List.of()));
        when(chroma.listCollections()).thenReturn(List.of(Map.of("name", "docs")));

        tools.search("q", null, null, exchange);
        assertThatThrownBy(() -> tools.search("q", "doc", null, exchange)).isInstanceOf(IllegalArgumentException.class);

        assertThat(journal.recent(10)).extracting(McpCallJournal.McpCall::tool, McpCallJournal.McpCall::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("spectra_search", McpCallJournal.Outcome.CALLER_ERROR),
                        org.assertj.core.groups.Tuple.tuple("spectra_search", McpCallJournal.Outcome.OK));
    }

    @Test
    void aBlankQuestionIsRefused() {
        assertThatThrownBy(() -> tools.ask(" ", null, null, exchange)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void previewPassesTheRegistryAnswerThrough() {
        when(ged.preview("abc")).thenReturn(new GedService.DocumentPreview(List.of("c1"), true));

        SpectraMcpTools.DocumentPreview preview = tools.previewDocument("abc", exchange);

        assertThat(preview.chunks()).containsExactly("c1");
        assertThat(preview.truncated()).isTrue();
    }
    @Test
    void searchReferenceReadsTheExactRevisionAndContinuesInsideALongChunk() {
        String revision = "a".repeat(64);
        String full = "x".repeat(2800);
        var source = new QueryResponse.Source("preview...", "source://connector/record", 0.1, null, null,
                new QueryResponse.Provenance(revision, "6", null, "Runbook", "https://example.org/runbook", full));
        when(ragService.retrieveContext(any())).thenReturn(context(List.of(source)));
        var hit = tools.search("q", null, null, exchange).passages().getFirst();
        assertThat(hit.text()).hasSize(2000);
        assertThat(hit.textTruncated()).isTrue();
        assertThat(hit.revision()).isEqualTo(revision);
        when(chroma.listCollections()).thenReturn(List.of(Map.of("name", "docs", "id", "collection-id")));
        when(chroma.getChunksByIndex(eq("collection-id"), eq("sha256"), eq(revision), anyList(),
                isNull(), eq("source://connector/record"))).thenReturn(Map.of(6, full));
        var page = tools.readDocument(hit.documentId(), 6, null, 256, exchange);
        assertThat(page.chunks().getFirst().text()).hasSize(256);
        var next = tools.readDocument(hit.documentId(), null, page.nextCursor(), 4000, exchange);
        assertThat(next.chunks().getFirst().characterOffset()).isEqualTo(256);
        assertThat(next.chunks().getFirst().text()).hasSize(full.length() - 256);
        assertThat(next.nextCursor()).isNull();
    }
}
