package fr.spectra.service;

import fr.spectra.model.TextChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextualHeaderTest {

    @Test
    void prefixesTitleSourceAndSection() {
        String embedded = ContextualHeader.apply("La valeur est 512.",
                Map.of("title", "Guide d'exploitation", "sourceFile", "guide.pdf", "section", "Réglages > Mémoire"));

        assertThat(embedded).isEqualTo("""
                Document : Guide d'exploitation — guide.pdf
                Section : Réglages > Mémoire

                La valeur est 512.""");
    }

    @Test
    void sourceAloneWhenNoTitle() {
        assertThat(ContextualHeader.apply("Texte.", Map.of("sourceFile", "a.txt")))
                .isEqualTo("Document : a.txt\n\nTexte.");
    }

    @Test
    void noMetadataLeavesTextUntouched() {
        assertThat(ContextualHeader.apply("Texte.", Map.of())).isEqualTo("Texte.");
        assertThat(ContextualHeader.apply("Texte.", null)).isEqualTo("Texte.");
    }

    @Test
    void disabledHeadersEmbedTheRawText() {
        List<TextChunk> chunks = List.of(new TextChunk("1", "Texte.", 0, "a.txt", Map.of("sourceFile", "a.txt")));

        assertThat(ContextualHeader.texts(chunks, false)).containsExactly("Texte.");
        assertThat(ContextualHeader.texts(chunks, true)).containsExactly("Document : a.txt\n\nTexte.");
    }
}
