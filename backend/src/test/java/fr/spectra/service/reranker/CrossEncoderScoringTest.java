package fr.spectra.service.reranker;

import fr.spectra.service.RerankerClient.RankedResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static fr.spectra.service.reranker.CrossEncoderScoring.Activation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Barème et classement du reranker exécuté dans la JVM.
 *
 * <p>L'inférence est une boîte noire tierce ; ce qui décide du contenu envoyé au LLM, c'est ce
 * qu'on en fait. D'où des tests sur cette partie-là uniquement, mais exhaustifs.
 */
class CrossEncoderScoringTest {

    // ── Barème ────────────────────────────────────────────────────────────────────────────

    @Test
    void sigmoide_reproduitLEchelleDeSentenceTransformers() {
        // Valeurs de référence de la sigmoïde : c'est ce que renvoie CrossEncoder.predict.
        assertThat(Activation.SIGMOID.apply(0f)).isEqualTo(0.5f);
        assertThat(Activation.SIGMOID.apply(2f)).isCloseTo(0.880797f, within(1e-5f));
        assertThat(Activation.SIGMOID.apply(-8.5f)).isCloseTo(0.000203f, within(1e-5f));
        assertThat(Activation.SIGMOID.apply(20f)).isBetween(0f, 1f);
    }

    @Test
    void logit_rendLaValeurBruteSansLaBorner() {
        assertThat(Activation.LOGIT.apply(-8.5f)).isEqualTo(-8.5f);
        assertThat(Activation.LOGIT.apply(12.25f)).isEqualTo(12.25f);
    }

    @Test
    void leBaremeNeChangeJamaisLOrdre() {
        // Propriété centrale : la sigmoïde est strictement croissante, donc changer d'activation
        // change l'échelle publiée mais JAMAIS ce que le RAG met dans son contexte. C'est ce qui
        // autorise -Dreranker.parity.scores=ignore sans renoncer au contrôle de l'ordre.
        float[] logits = {-8.5f, 3.25f, 0f, -0.5f, 12f, 1.75f};

        assertThat(CrossEncoderScoring.rank(logits, 6, Activation.SIGMOID))
                .extracting(RankedResult::index)
                .isEqualTo(CrossEncoderScoring.rank(logits, 6, Activation.LOGIT)
                        .stream().map(RankedResult::index).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sigmoid", "SIGMOID", " Sigmoid "})
    void parse_toleranteSurLaCasseEtLesEspaces(String value) {
        assertThat(Activation.parse(value)).isEqualTo(Activation.SIGMOID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"logit", "identity", "none", "raw"})
    void parse_synonymesDuLogitBrut(String value) {
        assertThat(Activation.parse(value)).isEqualTo(Activation.LOGIT);
    }

    @Test
    void parse_valeurInconnueRejeteeAvecLesValeursAcceptees() {
        // Une faute de frappe dans la configuration ne doit pas retomber en silence sur un
        // barème par défaut : les scores publiés changeraient d'échelle sans que rien ne l'indique.
        assertThatThrownBy(() -> Activation.parse("softmax"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("softmax")
                .hasMessageContaining("sigmoid");
    }

    // ── Classement ────────────────────────────────────────────────────────────────────────

    @Test
    void classeParScoreDecroissantEtConserveLesIndexDOrigine() {
        float[] logits = {0.1f, 5.0f, -3.0f, 2.0f};

        List<RankedResult> ranked = CrossEncoderScoring.rank(logits, 4, Activation.LOGIT);

        assertThat(ranked).extracting(RankedResult::index).containsExactly(1, 3, 0, 2);
        assertThat(ranked).extracting(RankedResult::score).containsExactly(5.0f, 2.0f, 0.1f, -3.0f);
    }

    @Test
    void topNBorneAuNombreDePassages() {
        // Même clamp que le service Python (top_n = min(top_n, len(documents))).
        assertThat(CrossEncoderScoring.rank(new float[]{1f, 2f}, 10, Activation.LOGIT)).hasSize(2);
        assertThat(CrossEncoderScoring.rank(new float[]{1f, 2f, 3f}, 2, Activation.LOGIT)).hasSize(2);
    }

    @Test
    void exAequoDepartagesParIndexCroissant() {
        // Sans départage déterministe, deux exécutions du MÊME modèle pourraient produire un
        // ordre différent, et la comparaison à la référence de parité deviendrait ininterprétable.
        float[] logits = {1f, 1f, 1f};

        assertThat(CrossEncoderScoring.rank(logits, 3, Activation.LOGIT))
                .extracting(RankedResult::index).containsExactly(0, 1, 2);
    }

    @Test
    void vivierVide_classementVide() {
        assertThat(CrossEncoderScoring.rank(new float[0], 5, Activation.SIGMOID)).isEmpty();
    }

    @Test
    void resultatImmuable() {
        List<RankedResult> ranked = CrossEncoderScoring.rank(new float[]{1f, 2f}, 2, Activation.LOGIT);

        assertThatThrownBy(() -> ranked.add(new RankedResult(9, 9f)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
