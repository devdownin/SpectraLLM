package fr.spectra.service;

import fr.spectra.dto.QueryResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires du scoring déterministe de RagAblationService (retrieval + percentile)
 * et du flux d'ablation en deux phases (génération avec le modèle du bras, notation par le
 * juge neutre, restauration). Les dépendances LLM/RAG sont mockées : aucun appel modèle réel.
 */
class RagAblationServiceTest {

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path workDir;

    private RagAblationService newService() {
        LlmChatClient chat = mock(LlmChatClient.class);
        when(chat.getActiveModel()).thenReturn("active-model");
        return new RagAblationService(mock(RagService.class), mock(QualityBenchmarkService.class),
                chat, new ModelSwitchCoordinator(chat, 2, 1));
    }

    // ── Flux d'ablation (deux phases + juge neutre + restauration) ──────────────

    @org.junit.jupiter.api.Test
    void runArm_genereAvecLeModeleDuBras_noteAvecLeJugeNeutre_puisRestaure() {
        // Chat mocké avec modèle actif mutable : réponses de juge déterministes.
        LlmChatClient chat = mock(LlmChatClient.class);
        java.util.concurrent.atomic.AtomicReference<String> active =
                new java.util.concurrent.atomic.AtomicReference<>("modele-initial");
        when(chat.getActiveModel()).thenAnswer(i -> active.get());
        org.mockito.Mockito.doAnswer(i -> { active.set(i.getArgument(0)); return null; })
                .when(chat).setActiveModel(org.mockito.ArgumentMatchers.anyString());
        when(chat.checkHealth()).thenReturn(new fr.spectra.dto.ServiceStatus(
                "llama-cpp", "http://test", true, "ok", 1, java.util.Map.of()));
        when(chat.chat(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    String system = inv.<String>getArgument(0);
                    if (system.contains("évaluateur expert")) return "{\"score\": 8, \"justification\": \"ok\"}";
                    if (system.contains("N'EST PAS disponible")) return "{\"refused\": true, \"justification\": \"ok\"}";
                    return "réponse";
                });

        ModelSwitchCoordinator coordinator = new ModelSwitchCoordinator(chat, 2, 1);
        // Benchmark réel (ressource classpath) + juge neutre configuré.
        QualityBenchmarkService quality = new QualityBenchmarkService(
                chat, coordinator, "judge-x", "", workDir.toString());

        RagService rag = mock(RagService.class);
        when(rag.query(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new fr.spectra.dto.QueryResponse("réponse pipeline", List.of(), 42L));

        RagAblationService svc = new RagAblationService(rag, quality, chat, coordinator);
        fr.spectra.dto.AblationReport report = svc.run(new fr.spectra.dto.AblationRequest(
                List.of(new fr.spectra.dto.AblationRequest.Arm("bras-ft", "modele-arm", true, null)),
                5, 1));

        assertThat(report.arms()).hasSize(1);
        var arm = report.arms().getFirst();
        assertThat(arm.model()).isEqualTo("modele-arm");
        // Le juge neutre a noté (8/10 partout) et il est tracé dans le rapport.
        assertThat(arm.quality().judgeModel()).isEqualTo("judge-x");
        assertThat(arm.quality().avgScore()).isEqualTo(8.0);

        // Phases : modèle du bras (génération) → juge neutre (notation) → restauration.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(chat);
        order.verify(chat).setActiveModel("modele-arm");
        order.verify(chat).setActiveModel("judge-x");
        order.verify(chat).setActiveModel("modele-initial");
        assertThat(chat.getActiveModel()).isEqualTo("modele-initial");
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

    // ── Statistiques (confiance) ────────────────────────────────────────────────

    @Test
    void mean_averagesValues() {
        assertThat(RagAblationService.mean(List.of(2.0, 4.0, 6.0))).isEqualTo(4.0);
        assertThat(RagAblationService.mean(List.of())).isZero();
    }

    @Test
    void std_singleRun_isZero() {
        // Une seule répétition : pas d'estimation de bruit possible.
        assertThat(RagAblationService.std(List.of(7.0))).isZero();
    }

    @Test
    void std_sampleStandardDeviation_usesNMinusOne() {
        // {2,4,6} : moyenne 4, variance échantillon = (4+0+4)/2 = 4, σ = 2.
        assertThat(RagAblationService.std(List.of(2.0, 4.0, 6.0))).isEqualTo(2.0);
    }

    @Test
    void std_identicalValues_isZero() {
        assertThat(RagAblationService.std(List.of(5.0, 5.0, 5.0))).isZero();
    }
}
