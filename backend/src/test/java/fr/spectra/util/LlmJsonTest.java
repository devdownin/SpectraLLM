package fr.spectra.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ces cas viennent de ce que les LLM produisent réellement : clôture Markdown, phrase
 * d'introduction, réponse vide. La fonction existait en double ; une seule des deux copies
 * aurait pu être corrigée.
 */
class LlmJsonTest {

    @Test
    void extraitLObjetDUneCloturseMarkdown() {
        assertThat(LlmJson.extract("```json\n{\"score\": 4}\n```")).isEqualTo("{\"score\": 4}");
    }

    @Test
    void extraitMalgreUnePhraseDIntroduction() {
        assertThat(LlmJson.extract("Voici mon analyse : {\"a\": 1} — j'espère que cela convient."))
                .isEqualTo("{\"a\": 1}");
    }

    @Test
    void prendLAccoladeFermanteLaPlusExterne() {
        assertThat(LlmJson.extract("{\"a\": {\"b\": 2}}")).isEqualTo("{\"a\": {\"b\": 2}}");
    }

    @Test
    void rendNullQuandIlNyAPasDObjet() {
        assertThat(LlmJson.extract("aucun json ici")).isNull();
        assertThat(LlmJson.extract("")).isNull();
        assertThat(LlmJson.extract(null)).isNull();
        assertThat(LlmJson.extract("} mal ordonne {")).isNull();
    }
}
