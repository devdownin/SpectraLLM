package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.CitationReport;
import fr.spectra.dto.CitationReport.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CitationVerifierTest {

    private static final List<String> PASSAGES = List.of(
            "La température de service est de 60 °C.",
            "Le délai de garantie est de deux ans.");

    private RerankerClient reranker;
    private SpectraProperties props;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        reranker = mock(RerankerClient.class);
        when(reranker.isAvailable()).thenReturn(true);
        // Score élevé si la phrase et le passage parlent de la même chose.
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            String claim = inv.getArgument(0);
            List<String> docs = inv.getArgument(1);
            return java.util.stream.IntStream.range(0, docs.size())
                    .mapToObj(i -> new RerankerClient.RankedResult(i,
                            sharesKeyword(claim, docs.get(i)) ? 0.9f : 0.05f))
                    .toList();
        });
        props = mock(SpectraProperties.class);
        meters = new SimpleMeterRegistry();
        rag(null, null, null);
    }

    private static boolean sharesKeyword(String claim, String passage) {
        return (claim.contains("60") && passage.contains("60")) || (claim.contains("ans") && passage.contains("ans"));
    }

    private void rag(Boolean check, Float minScore, Boolean strict) {
        when(props.rag()).thenReturn(new SpectraProperties.RagProperties(null, null, null, null, check, minScore, strict));
    }

    private CitationVerifier verifier() {
        return new CitationVerifier(Optional.of(reranker), props, meters);
    }

    @Test
    void sentences_rattacheLaCitationIsoleeALaPhrasePrecedente() {
        List<CitationVerifier.Sentence> s = CitationVerifier.sentences(
                "La température est de 60 °C. [1] La garantie dure deux ans [2]. Merci.");

        assertThat(s).extracting(CitationVerifier.Sentence::citations)
                .containsExactly(List.of(1), List.of(2), List.of());
        assertThat(s.getFirst().text()).startsWith("La température").endsWith("[1]");
    }

    @Test
    void sentences_citationRenvoyeeALaLigne_resteALaPhrasePrecedente() {
        List<CitationVerifier.Sentence> s = CitationVerifier.sentences(
                "La température est de 60 °C.\n[1]\nLa garantie dure deux ans [2].");

        assertThat(s).extracting(CitationVerifier.Sentence::citations).containsExactly(List.of(1), List.of(2));
    }

    @Test
    void sentences_lisLesCitationsMultiples() {
        assertThat(CitationVerifier.sentences("Deux faits [1, 2] et [3;4].").getFirst().citations())
                .containsExactly(1, 2, 3, 4);
    }

    @Test
    void verify_classeEtayeeNonEtayeeEtInvalide() {
        CitationReport report = verifier().verify(
                "La température est de 60 °C [1]. La garantie dure deux ans [1]. Le prix est bas [7]. Bonjour.",
                PASSAGES).orElseThrow();

        assertThat(report.checks()).extracting(CitationReport.SentenceCheck::status)
                .containsExactly(Status.SUPPORTED, Status.UNSUPPORTED, Status.INVALID_CITATION);
        assertThat(report.citedSentences()).isEqualTo(3);
        assertThat(report.supportedSentences()).isEqualTo(1);
        assertThat(report.unsupportedSentences()).isEqualTo(2);
        assertThat(report.invalidCitations()).isEqualTo(1);
        assertThat(report.uncitedSentences()).isEqualTo(1);
        assertThat(report.faithfulness()).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(meters.counter("spectra.rag.citations", "status", "supported").count()).isEqualTo(1.0);
    }

    @Test
    void verify_scoreLaPhraseSansSesCitationsContreLesSeulsPassagesCites() {
        verifier().verify("La garantie dure deux ans [2].", PASSAGES);

        verify(reranker).rerank(eq("La garantie dure deux ans ."), eq(List.of(PASSAGES.get(1))), eq(1));
    }

    @Test
    void verify_unPassageCiteSuffitParmiPlusieurs() {
        CitationReport report = verifier().verify("La garantie dure deux ans [1, 2].", PASSAGES).orElseThrow();

        assertThat(report.checks().getFirst().status()).isEqualTo(Status.SUPPORTED);
        assertThat(report.checks().getFirst().bestScore()).isEqualTo(0.9f);
    }

    @Test
    void verify_seuilConfigurable() {
        rag(null, 0.95f, null);

        CitationReport report = verifier().verify("La température est de 60 °C [1].", PASSAGES).orElseThrow();

        assertThat(report.checks().getFirst().status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(report.minScore()).isEqualTo(0.95f);
    }

    @Test
    void verify_sansCitation_faithfulnessNulle() {
        CitationReport report = verifier().verify("Aucune source ici.", PASSAGES).orElseThrow();

        assertThat(report.citedSentences()).isZero();
        assertThat(report.faithfulness()).isNull();
        verify(reranker, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void verify_indisponibleSansRerankerOuDesactive() {
        when(reranker.isAvailable()).thenReturn(false);
        assertThat(verifier().verify("Fait [1].", PASSAGES)).isEmpty();

        when(reranker.isAvailable()).thenReturn(true);
        rag(false, null, null);
        assertThat(verifier().verify("Fait [1].", PASSAGES)).isEmpty();

        assertThat(new CitationVerifier(Optional.empty(), props, meters).verify("Fait [1].", PASSAGES)).isEmpty();
    }

    @Test
    void verify_erreurDuReranker_nestJamaisBloquante() {
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenThrow(new IllegalStateException("onnx"));

        assertThat(verifier().verify("La température est de 60 °C [1].", PASSAGES)).isEmpty();
    }

    @Test
    void withoutUnsupported_retireLesPhrasesNonEtayeesEtGardeLesNonCitees() {
        String answer = "La température est de 60 °C [1]. La garantie dure deux ans [1]. Bonne journée.";
        CitationVerifier v = verifier();
        CitationReport report = v.verify(answer, PASSAGES).orElseThrow();

        assertThat(v.withoutUnsupported(answer, report, "repli"))
                .isEqualTo("La température est de 60 °C [1]. Bonne journée.");
    }

    @Test
    void withoutUnsupported_rienDEtaye_renvoieLeRepli() {
        String answer = "La garantie dure deux ans [1].";
        CitationVerifier v = verifier();

        assertThat(v.withoutUnsupported(answer, v.verify(answer, PASSAGES).orElseThrow(), "repli"))
                .isEqualTo("repli");
    }

    @Test
    void strict_suitLaConfiguration() {
        assertThat(verifier().strict()).isFalse();
        rag(null, null, true);
        assertThat(verifier().strict()).isTrue();
    }
}
