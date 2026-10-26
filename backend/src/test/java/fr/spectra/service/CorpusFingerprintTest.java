package fr.spectra.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CorpusFingerprintTest {
    @Test
    void ignoresIterationOrderButDetectsReplacementAndMetadataChanges() {
        var first = new CorpusFingerprint();
        first.add("a", "texte A", "a.txt", "sha-a", "titre A");
        first.add("b", "texte B", "b.txt", "sha-b", "titre B");
        var reordered = new CorpusFingerprint();
        reordered.add("b", "texte B", "b.txt", "sha-b", "titre B");
        reordered.add("a", "texte A", "a.txt", "sha-a", "titre A");
        assertThat(reordered.value()).isEqualTo(first.value());
        var replacement = new CorpusFingerprint();
        replacement.add("a", "texte modifié", "a.txt", "sha-a", "titre A");
        replacement.add("b", "texte B", "b.txt", "sha-b", "titre B");
        assertThat(replacement.count()).isEqualTo(first.count());
        assertThat(replacement.value()).isNotEqualTo(first.value());
        var headerChanged = new CorpusFingerprint();
        headerChanged.add("a", "texte A", "a.txt", "sha-a", "autre titre");
        headerChanged.add("b", "texte B", "b.txt", "sha-b", "titre B");
        assertThat(headerChanged.value()).isNotEqualTo(first.value());
    }

    @Test
    void distinguishesNullAndEmptyFieldsAndFieldBoundaries() {
        var absent = new CorpusFingerprint();
        absent.add("id", "ab", "c", null, null);
        var empty = new CorpusFingerprint();
        empty.add("id", "ab", "c", "", null);
        var shifted = new CorpusFingerprint();
        shifted.add("id", "a", "bc", null, null);
        assertThat(absent.value()).isNotEqualTo(empty.value()).isNotEqualTo(shifted.value());
    }
}
