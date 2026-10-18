package fr.spectra.service;

import org.junit.jupiter.api.Test;

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
    void termsForBm25_splitFileNameAndSkipLabels() {
        assertThat(ContextualHeader.terms(Map.of("title", "Contrat cadre", "sourceFile", "contrat_cadre-2026.pdf",
                "section", "Résiliation > Préavis")))
                .isEqualTo("Contrat cadre contrat cadre-2026 pdf Résiliation > Préavis");
        assertThat(ContextualHeader.terms(Map.of("chunkIndex", "3"))).isNull();
        assertThat(ContextualHeader.terms(null)).isNull();
    }
}
