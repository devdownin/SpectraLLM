package fr.spectra.dto;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalibrationRequestTest {

    @Test
    void produitCartesienDesValeurs() {
        AblationRequest req = new CalibrationRequest(List.of(0f, 0.2f), List.of(0, 1), List.of("mmr"), 5, 2)
                .toAblationRequest();

        assertThat(req.arms()).hasSize(4);
        assertThat(req.maxContextChunks()).isEqualTo(5);
        assertThat(req.runs()).isEqualTo(2);
        AblationRequest.Arm last = req.arms().getLast();
        assertThat(last.label()).isEqualTo("minScore=0.2 · voisins=1 · dédup=mmr");
        assertThat(last.useRag()).isTrue();
        assertThat(last.overrides().rerankMinScore()).isEqualTo(0.2f);
        assertThat(last.overrides().neighborWindow()).isEqualTo(1);
        assertThat(last.overrides().dedupStrategy()).isEqualTo("mmr");
    }

    @Test
    void listeAbsente_laisseLaConfiguration() {
        AblationRequest req = new CalibrationRequest(List.of(0.1f), null, List.of(), null, null).toAblationRequest();

        assertThat(req.arms()).singleElement().satisfies(arm -> {
            assertThat(arm.label()).isEqualTo("minScore=0.1 · voisins=config · dédup=config");
            assertThat(arm.overrides().neighborWindow()).isNull();
            assertThat(arm.overrides().dedupStrategy()).isNull();
        });
    }

    @Test
    void grilleTropGrande_refusee() {
        List<Float> scores = Collections.nCopies(5, 0.1f);
        List<Integer> windows = Collections.nCopies(4, 0);

        assertThatThrownBy(() -> new CalibrationRequest(scores, windows, null, null, null).toAblationRequest())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20 combinaisons");
    }
}
