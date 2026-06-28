package fr.spectra.service;

import fr.spectra.dto.QueryResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires du scoring déterministe de RagAblationService (retrieval + percentile).
 * Les dépendances LLM/RAG sont mockées : aucun appel modèle n'est nécessaire.
 */
class RagAblationServiceTest {

    private RagAblationService newService() {
        LlmChatClient chat = mock(LlmChatClient.class);
        when(chat.getActiveModel()).thenReturn("active-model");
        return new RagAblationService(mock(RagService.class), mock(QualityBenchmarkService.class), chat);
    }

    private QueryResponse.Source source(String file) {
        return new QueryResponse.Source("texte", file, 0.1);
    }

    // ── Retrieval ──────────────────────────────────────────────────────────────

    @Test
    void hitAtRankOne_givesPerfectScores() {
        RagAblationService svc = newService();
        List<QueryResponse.Source> sources = List.of(source("doc_a.pdf"), source("doc_b.pdf"));

        RagAblationService.RetrievalHit hit = svc.scoreRetrieval(sources, List.of("doc_a"), 5);

        assertThat(hit.hit()).isTrue();
        assertThat(hit.reciprocalRank()).isEqualTo(1.0);
        assertThat(hit.recall()).isEqualTo(1.0);
    }

    @Test
    void hitAtRankThree_givesReciprocalRankOneThird() {
        RagAblationService svc = newService();
        List<QueryResponse.Source> sources =
                List.of(source("x.pdf"), source("y.pdf"), source("guide_securite.pdf"));

        RagAblationService.RetrievalHit hit = svc.scoreRetrieval(sources, List.of("guide_securite"), 5);

        assertThat(hit.hit()).isTrue();
        assertThat(hit.reciprocalRank()).isEqualTo(1.0 / 3.0);
    }

    @Test
    void matchOutsideTopK_isMiss() {
        RagAblationService svc = newService();
        List<QueryResponse.Source> sources =
                List.of(source("x.pdf"), source("y.pdf"), source("cible.pdf"));

        RagAblationService.RetrievalHit hit = svc.scoreRetrieval(sources, List.of("cible"), 2);

        assertThat(hit.hit()).isFalse();
        assertThat(hit.reciprocalRank()).isZero();
        assertThat(hit.recall()).isZero();
    }

    @Test
    void recall_isFractionOfExpectedSourcesFound() {
        RagAblationService svc = newService();
        List<QueryResponse.Source> sources = List.of(source("a.pdf"), source("b.pdf"));

        RagAblationService.RetrievalHit hit =
                svc.scoreRetrieval(sources, List.of("a", "b", "c"), 5);

        assertThat(hit.hit()).isTrue();
        assertThat(hit.recall()).isEqualTo(2.0 / 3.0);
    }

    @Test
    void noSources_isMiss() {
        RagAblationService svc = newService();
        RagAblationService.RetrievalHit hit = svc.scoreRetrieval(List.of(), List.of("a"), 5);
        assertThat(hit.hit()).isFalse();
    }

    @Test
    void matchingIsCaseInsensitive() {
        RagAblationService svc = newService();
        List<QueryResponse.Source> sources = List.of(source("Procedure_INTERVENTION.pdf"));
        RagAblationService.RetrievalHit hit =
                svc.scoreRetrieval(sources, List.of("procedure_intervention"), 5);
        assertThat(hit.hit()).isTrue();
    }

    // ── Percentile ───────────────────────────────────────────────────────────────

    @Test
    void percentile_p50_returnsMedianByNearestRank() {
        RagAblationService svc = newService();
        assertThat(svc.percentile(List.of(10L, 20L, 30L, 40L, 50L), 50)).isEqualTo(30.0);
    }

    @Test
    void percentile_emptyList_returnsZero() {
        RagAblationService svc = newService();
        assertThat(svc.percentile(List.of(), 50)).isZero();
    }
}
