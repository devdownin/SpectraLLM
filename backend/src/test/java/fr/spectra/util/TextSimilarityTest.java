package fr.spectra.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextSimilarityTest {

    @Test
    void deuxTextesIdentiquesDonnentUn() {
        assertThat(TextSimilarity.jaccard("le chat dort", "le chat dort")).isEqualTo(1.0);
    }

    @Test
    void laCasseEstIgnoree() {
        assertThat(TextSimilarity.jaccard("Le Chat", "le chat")).isEqualTo(1.0);
    }

    @Test
    void aucunMotCommunDonneZero() {
        assertThat(TextSimilarity.jaccard("chat", "voiture")).isEqualTo(0.0);
    }

    @Test
    void recouvrementPartiel() {
        // {le, chat} ∩ {le, chien} = {le} ; union = 3
        assertThat(TextSimilarity.jaccard("le chat", "le chien")).isEqualTo(1.0 / 3.0);
    }

    @Test
    void deuxTextesVidesSontReputesIdentiques() {
        // Cas dégénéré assumé : les appelants s'en servent pour écarter des doublons, et
        // traiter « vide vs vide » comme une divergence produirait de faux positifs.
        assertThat(TextSimilarity.jaccard("", "")).isEqualTo(1.0);
    }
}
