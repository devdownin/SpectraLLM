package fr.spectra.dto;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryResponseContextTest {
    @Test
    void copiesPreserveCitationsAndContextMeasurement() {
        var report = new CitationReport(1, 1, 0, 0, 0, 1.0, 0.2f, List.of());
        var original = new QueryResponse("answer", List.of(), 3L);
        assertThat(original.contextChars()).isNull();
        var measured = original.withCitations("verified", report).withContextChars(4000);
        assertThat(measured.contextChars()).isEqualTo(4000);
        assertThat(measured.citations()).isSameAs(report);
        assertThat(measured.withCitations("changed", report).contextChars()).isEqualTo(4000);
        assertThatThrownBy(() -> original.withContextChars(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
