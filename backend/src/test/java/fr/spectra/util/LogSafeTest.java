package fr.spectra.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSafeTest {

    @Test
    void texteMasqueParDefautMaisCorrelable() {
        String question = "Quel est le salaire de Mme Durand ?";
        String masque = LogSafe.text(question, false);

        assertThat(masque).doesNotContain("salaire").doesNotContain("Durand");
        assertThat(masque).startsWith("[" + question.length() + " car., #");
        assertThat(LogSafe.text(question, false)).isEqualTo(masque);
        assertThat(LogSafe.text("Autre question", false)).isNotEqualTo(masque);
    }

    @Test
    void contenuIntegralSurDemandeExplicite() {
        assertThat(LogSafe.text("Bonjour", true)).isEqualTo("Bonjour");
        assertThat(LogSafe.url("https://u:p@h.example/x?token=1", true)).isEqualTo("https://u:p@h.example/x?token=1");
    }

    @Test
    void urlSansIdentifiantsNiRequeteNiFragment() {
        assertThat(LogSafe.url("https://user:secret@docs.interne:8443/a/b.pdf?token=abc#p2", false))
                .isEqualTo("https://docs.interne:8443/a/b.pdf [paramètres masqués]")
                .doesNotContain("secret").doesNotContain("token");
        assertThat(LogSafe.url("https://docs.interne/a/b.pdf", false)).isEqualTo("https://docs.interne/a/b.pdf");
    }

    @Test
    void entreesInvalidesOuNullesNeFontPasEchouerLeJournal() {
        assertThat(LogSafe.text(null, false)).isEqualTo("null");
        assertThat(LogSafe.url(null, false)).isEqualTo("null");
        assertThat(LogSafe.url("pas une url ::", false)).startsWith("[URL illisible");
    }
}
