package fr.spectra.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Défauts appliqués par le compact constructor de {@link FineTuningRequest}.
 *
 * <p>Le point sensible est {@code loraAlpha} : c'est le rapport {@code alpha / r} qui fixe
 * l'amplitude effective de l'adaptation LoRA. Un alpha constant faisait dériver ce rapport
 * avec le rang — et le formulaire de fine-tuning n'envoie pas ce champ, donc le défaut est
 * la valeur réellement utilisée en pratique.</p>
 */
class FineTuningRequestTest {

    private static FineTuningRequest withRank(Integer loraRank) {
        return new FineTuningRequest("mon-modele", null, loraRank, null, null,
                null, null, null, null, null, null);
    }

    @ParameterizedTest(name = "rang {0} → alpha {1}")
    @CsvSource({"4, 8", "8, 16", "16, 32", "64, 128", "256, 512"})
    void loraAlphaEstDeriveDuRangPourGarderUneEchelleConstante(int rank, int expectedAlpha) {
        FineTuningRequest request = withRank(rank);

        assertThat(request.loraAlpha()).isEqualTo(expectedAlpha);
        assertThat(request.loraAlpha()).isEqualTo(2 * request.loraRank());
    }

    @Test
    void loraAlphaSuitLeRangParDefautQuandAucunRangNEstFourni() {
        FineTuningRequest request = withRank(null);

        assertThat(request.loraRank()).isEqualTo(64);
        assertThat(request.loraAlpha()).isEqualTo(128);
    }

    @Test
    void unLoraAlphaExpliciteNEstJamaisEcrase() {
        FineTuningRequest request = new FineTuningRequest("mon-modele", null, 8, 64, null,
                null, null, null, null, null, null);

        assertThat(request.loraAlpha()).isEqualTo(64);
    }

    @Test
    void loraAlphaResteDansLeDomaineValideMemeSiLeRangEstHorsBornes() {
        // loraRank hors bornes est rejeté par @Max(256) à la validation ; le défaut dérivé ne
        // doit pas produire au passage un alpha qui violerait @Max(512).
        assertThat(withRank(1_000).loraAlpha()).isEqualTo(512);
    }

    @Test
    void orpoDesactiveDpo() {
        FineTuningRequest request = new FineTuningRequest("mon-modele", null, null, null, null,
                null, null, null, true, true, null);

        assertThat(request.orpoEnabled()).isTrue();
        assertThat(request.dpoEnabled()).isFalse();
    }
}
