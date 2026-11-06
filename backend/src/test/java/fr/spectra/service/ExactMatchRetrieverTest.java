package fr.spectra.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExactMatchRetrieverTest {

    private static ChromaDbClient.ChunkHit hit(String text, String sha) {
        return new ChromaDbClient.ChunkHit(text, Map.of("sha256", sha, "sourceFile", sha + ".txt"));
    }

    @Test
    void withoutFilterReturnsTheHitsUpToTheLimit() {
        ChromaDbClient chroma = mock(ChromaDbClient.class);
        when(chroma.findChunksContaining(eq("col"), eq("ENG-1"), any(), eq(2)))
                .thenReturn(List.of(hit("a", "s1"), hit("b", "s2")));

        assertThat(ExactMatchRetriever.find(chroma, "col", "ENG-1",
                RetrievalFilterResolver.ResolvedFilter.NONE, 2)).extracting(ChromaDbClient.ChunkHit::text)
                .containsExactly("a", "b");
    }

    @Test
    void filterIsEnforcedAfterTheBroadChromaPrefilter() {
        ChromaDbClient chroma = mock(ChromaDbClient.class);
        when(chroma.findChunksContaining(eq("col"), eq("ENG-1"), any(), anyInt()))
                .thenReturn(List.of(hit("interdit", "autre"), hit("permis", "ok"), hit("permis 2", "ok")));
        var filter = new RetrievalFilterResolver.ResolvedFilter(Set.of("ok"), null);

        List<ChromaDbClient.ChunkHit> hits = ExactMatchRetriever.find(chroma, "col", "ENG-1", filter, 1);

        assertThat(hits).extracting(ChromaDbClient.ChunkHit::text).containsExactly("permis");
        // Marge de lecture : le postfiltre peut écarter des homonymes.
        verify(chroma).findChunksContaining(eq("col"), eq("ENG-1"), any(), eq(4));
    }

    @Test
    void aFilterThatAdmitsNothingNeverQueriesChroma() {
        ChromaDbClient chroma = mock(ChromaDbClient.class);
        var nothing = new RetrievalFilterResolver.ResolvedFilter(Set.of(), Set.of());

        assertThat(ExactMatchRetriever.find(chroma, "col", "ENG-1", nothing, 5)).isEmpty();
        verifyNoInteractions(chroma);
    }
}
