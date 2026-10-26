package fr.spectra.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FilteredVectorSearchTest {
    private static final RetrievalFilterResolver.ResolvedFilter FILTER =
            new RetrievalFilterResolver.ResolvedFilter(Set.of("allowed"), Set.of("report.pdf"));

    @Test
    void excludedHomonymCannotDisplaceAnAllowedDocumentAndAlignmentIsPreserved() {
        ChromaDbClient client = mock(ChromaDbClient.class);
        when(client.query(eq("c"), anyList(), eq(1), any())).thenReturn(row(
                List.of("excluded"), List.of(meta("excluded")), List.of(0.01), List.of("bad")));
        when(client.query(eq("c"), anyList(), eq(2), any())).thenReturn(row(
                List.of("excluded", "permitted"), List.of(meta("excluded"), meta("allowed")),
                List.of(0.01, 0.2), List.of("bad", "good")));
        ChromaQueryResult result = ChromaQueryResult.from(
                FilteredVectorSearch.query(client, "c", List.of(1f), 1, FILTER));
        assertThat(result.documents()).containsExactly("permitted");
        assertThat(result.ids()).containsExactly("good");
        assertThat(result.distances()).containsExactly(0.2);
        assertThat(result.metadatas()).containsExactly(meta("allowed"));
    }

    @Test
    void historicalChunkWithoutFingerprintStillUsesItsSource() {
        ChromaDbClient client = mock(ChromaDbClient.class);
        when(client.query(eq("c"), anyList(), anyInt(), any())).thenReturn(row(
                List.of("legacy"), List.of(Map.of("sourceFile", "report.pdf")), List.of(0.1), List.of("old")));
        assertThat(ChromaQueryResult.from(FilteredVectorSearch.query(client, "c", List.of(1f), 2, FILTER))
                .documents()).containsExactly("legacy");
    }

    @Test
    void missingProvenanceFailsClosedAndEmptyFilterMakesNoRequest() {
        ChromaDbClient client = mock(ChromaDbClient.class);
        when(client.query(eq("c"), anyList(), anyInt(), any())).thenReturn(row(
                List.of("unknown"), List.of(Map.of()), List.of(0.1), List.of("unknown")));
        assertThat(ChromaQueryResult.from(FilteredVectorSearch.query(client, "c", List.of(1f), 2, FILTER))
                .documents()).isEmpty();
        clearInvocations(client);
        assertThat(FilteredVectorSearch.query(client, "c", List.of(1f), 2,
                new RetrievalFilterResolver.ResolvedFilter(Set.of(), Set.of()))).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void pathologicalHomonymsCannotCauseUnboundedOverExtraction() {
        ChromaDbClient client = mock(ChromaDbClient.class);
        when(client.query(eq("c"), anyList(), anyInt(), any())).thenAnswer(call -> {
            int n = call.getArgument(2);
            assertThat(n).isLessThanOrEqualTo(FilteredVectorSearch.MAX_CANDIDATES);
            return row(java.util.Collections.nCopies(n, "excluded"),
                    java.util.Collections.nCopies(n, meta("excluded")),
                    java.util.Collections.nCopies(n, 0.1), java.util.Collections.nCopies(n, "bad"));
        });
        assertThat(ChromaQueryResult.from(FilteredVectorSearch.query(client, "c", List.of(1f), 1, FILTER))
                .documents()).isEmpty();
        verify(client).query(eq("c"), anyList(), eq(FilteredVectorSearch.MAX_CANDIDATES), any());
    }

    private static Map<String, String> meta(String sha) {
        return Map.of("sourceFile", "report.pdf", "sha256", sha);
    }
    private static Map<String, Object> row(List<String> docs, List<Map<String, String>> metas,
                                           List<Double> distances, List<String> ids) {
        return Map.of("documents", List.of(docs), "metadatas", List.of(metas),
                "distances", List.of(distances), "ids", List.of(ids));
    }
}
