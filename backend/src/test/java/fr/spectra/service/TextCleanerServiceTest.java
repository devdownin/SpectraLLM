package fr.spectra.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextCleanerServiceTest {

    private TextCleanerService cleaner;

    @BeforeEach
    void setUp() {
        cleaner = new TextCleanerService();
    }

    @Test
    void clean_returnsEmptyOnNull() {
        assertThat(cleaner.clean(null)).isEmpty();
    }

    @Test
    void clean_returnsEmptyOnBlank() {
        assertThat(cleaner.clean("   \n  ")).isEmpty();
    }

    @Test
    void clean_removesPageMarkers() {
        String input = "Début du texte\n- 42 -\nSuite du texte";
        assertThat(cleaner.clean(input)).doesNotContain("- 42 -");
        assertThat(cleaner.clean(input)).contains("Début du texte");
        assertThat(cleaner.clean(input)).contains("Suite du texte");
    }

    @Test
    void clean_removesHeaderFooterPatterns() {
        String input = "Contenu utile\nPage 3/10\nAutre contenu";
        String result = cleaner.clean(input);
        assertThat(result).doesNotContain("Page 3/10");
        assertThat(result).contains("Contenu utile");
    }

    @Test
    void clean_collapseMultipleSpaces() {
        String input = "Mot1   Mot2     Mot3";
        assertThat(cleaner.clean(input)).isEqualTo("Mot1 Mot2 Mot3");
    }

    @Test
    void clean_collapseMultipleNewlines() {
        String input = "Paragraphe 1\n\n\n\nParagraphe 2";
        String result = cleaner.clean(input);
        assertThat(result).doesNotContain("\n\n\n");
        assertThat(result).contains("Paragraphe 1");
        assertThat(result).contains("Paragraphe 2");
    }

    @Test
    void clean_replacesTableBorders() {
        String input = "col1 | col2 | col3";
        assertThat(cleaner.clean(input)).doesNotContain("|");
    }

    @Test
    void clean_replacesOcrLigatures() {
        String input = "eﬀicace coﬁn aﬄuence"; // Note: aﬄuence utilise la ligature 'ffl' (ﬄ)
        String result = cleaner.clean(input);
        assertThat(result).contains("efficace");
        assertThat(result).contains("cofin");
        assertThat(result).contains("affluence");
    }

    @Test
    void clean_normalizeBulletPoints() {
        String input = "• Premier point\n● Deuxième point\n■ Troisième point";
        String result = cleaner.clean(input);
        assertThat(result).doesNotContain("•");
        assertThat(result).doesNotContain("●");
        assertThat(result).doesNotContain("■");
    }

    @Test
    void clean_preservesNormalText() {
        String input = "Ce texte normal doit être conservé intégralement.";
        assertThat(cleaner.clean(input)).isEqualTo(input);
    }
}
