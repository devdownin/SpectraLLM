package fr.spectra.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ChromaQueryResultTest {
    @Test void absentOrNullFirstRowsAreEmpty() {
        assertThat(ChromaQueryResult.from(null).documents()).isEmpty();
        assertThat(ChromaQueryResult.from(Map.of("documents", Arrays.asList((Object) null))).documents()).isEmpty();
    }

    @Test void shortMetadataAndDistancesStayAlignedAfterInvalidDocumentsAreRemoved() {
        var result = ChromaQueryResult.from(Map.of(
                "documents", List.of(Arrays.asList(null, " ", "preuve")),
                "metadatas", List.of(Arrays.asList(null, null, Map.of("sourceFile", "preuve.txt"))),
                "distances", List.of(Arrays.asList(.1, .2, .3)),
                "ids", List.of(List.of("nul", "vide", "id-preuve"))));
        assertThat(result.documents()).containsExactly("preuve");
        assertThat(result.metadatas()).containsExactly(Map.of("sourceFile", "preuve.txt"));
        assertThat(result.distances()).containsExactly(.3);
        assertThat(result.ids()).containsExactly("id-preuve");
    }

    @Test void unknownAndNonFiniteDistancesAreNotPerfectMatches() {
        var result = ChromaQueryResult.from(Map.of(
                "documents", List.of(List.of("un", "deux", "trois", "quatre")),
                "distances", List.of(Arrays.asList(Double.NaN, Double.POSITIVE_INFINITY, null))));
        assertThat(result.distances()).containsExactly(1.0, 1.0, 1.0, 1.0);
        assertThat(result.metadatas()).hasSize(4).allMatch(Map::isEmpty);
        assertThat(result.ids()).containsExactly("__vec_0", "__vec_1", "__vec_2", "__vec_3");
    }
}
