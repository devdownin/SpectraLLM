package fr.spectra.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntityExtractorTest {

    @Test
    @DisplayName("clés de ticket, références de PR, noms de code et noms de produit")
    void extractsTheIdentifierFamilies() {
        List<String> entities = EntityExtractor.extract(
                "Qui a fermé ENG-39100 via la PR #20501 en renommant max_file_size, et que fait MedThink ?", 10);

        assertThat(entities).containsExactly("ENG-39100", "#20501", "max_file_size", "MedThink");
    }

    @Test
    @DisplayName("notation pointée : une métrique comme stream.timebox_finalized")
    void extractsDottedMetricNames() {
        assertThat(EntityExtractor.extract("Quel est le label de stream.timebox_finalized ?", 5))
                .containsExactly("stream.timebox_finalized");
    }

    @Test
    @DisplayName("l'ordre d'apparition prime, les doublons (casse comprise) sont retirés")
    void keepsAppearanceOrderAndDropsDuplicates() {
        assertThat(EntityExtractor.extract("DES-1 puis ENG-2 puis des-1 puis DES-1", 10))
                .containsExactly("DES-1", "ENG-2");
    }

    @Test
    @DisplayName("le nombre d'entités est borné : les premières seulement")
    void capsTheNumberOfEntities() {
        assertThat(EntityExtractor.extract("AB-1 CD-2 EF-3 GH-4", 2)).containsExactly("AB-1", "CD-2");
        assertThat(EntityExtractor.extract("AB-1 CD-2", 0)).isEmpty();
    }

    @Test
    @DisplayName("sigles, mots courants et abréviations ne sont pas des entités")
    void ignoresAcronymsAndOrdinaryWords() {
        assertThat(EntityExtractor.extract(
                "Que dit la politique SLO sur le GPU, par ex. e.g. i.e. la latence TTFT ?", 10)).isEmpty();
    }

    @Test
    @DisplayName("un nombre sans « # » et une entité dans une URL ne sont pas confondus avec une PR")
    void doesNotTreatBareNumbersAsReferences() {
        assertThat(EntityExtractor.extract("Le seuil est de 20501 ms &#8217; et 3 PR ouvertes", 10)).isEmpty();
    }

    @Test
    @DisplayName("texte vide ou nul : aucune entité")
    void emptyInput() {
        assertThat(EntityExtractor.extract(null, 5)).isEmpty();
        assertThat(EntityExtractor.extract("   ", 5)).isEmpty();
    }

    @Test
    @DisplayName("entrées pathologiques (alerte CodeQL) : analyse linéaire, aucune entité, vite")
    void pathologicalInputsAreScannedInLinearTime() {
        String dotted = "ba" + ".ba.0".repeat(200_000) + "Z";
        String camel = "bA0" + "AbA0".repeat(200_000) + "_";

        long start = System.nanoTime();
        List<String> a = EntityExtractor.extract(dotted, 5);
        List<String> b = EntityExtractor.extract(camel, 5);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(500);
        assertThat(a).isEmpty();
        assertThat(b).isEmpty();
    }

    @Test
    @DisplayName("un « identifiant » de plusieurs milliers de caractères n'en est pas un")
    void absurdlyLongTokensAreNotEntities() {
        assertThat(EntityExtractor.extract("a_".repeat(EntityExtractor.MAX_ENTITY_LENGTH) + "bc", 5)).isEmpty();
        assertThat(EntityExtractor.extract("a_b".repeat(10), 5)).hasSize(1);
    }

    @Test
    @DisplayName("l'entrée est tronquée : un identifiant au-delà de la limite est ignoré")
    void inputBeyondTheLimitIsTruncated() {
        String padding = "mot ".repeat(EntityExtractor.MAX_INPUT_CHARS / 4 + 10);

        assertThat(EntityExtractor.extract(padding + "ENG-39100", 5)).isEmpty();
        assertThat(EntityExtractor.extract("ENG-39100 " + padding, 5)).containsExactly("ENG-39100");
    }

    @Test
    @DisplayName("ponctuation et chemins autour des identifiants")
    void identifiersSurroundedByPunctuation() {
        assertThat(EntityExtractor.extract("(voir ENG-39100), puis `max_file_size`. Et linear/DES-12 ?", 10))
                .containsExactly("ENG-39100", "max_file_size", "DES-12");
    }
}
