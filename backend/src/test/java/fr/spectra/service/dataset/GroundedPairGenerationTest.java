package fr.spectra.service.dataset;

import fr.spectra.model.RagPromptFormat;
import fr.spectra.model.TrainingPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Construction des paires <b>ancrées</b> — la moitié « dataset » du correctif F14.
 *
 * <p>Le point n'est pas d'ajouter du contexte pour la forme : c'est d'apprendre au modèle à
 * <i>lire</i> un contexte qui contient des passages hors sujet, à citer le bon numéro, et à
 * s'abstenir quand la réponse n'y est pas. Chaque test ci-dessous fige l'une de ces trois
 * propriétés — les trois choses qu'un contexte à un seul passage, toujours pertinent, toujours
 * cité {@code [1]}, n'enseignerait pas.
 */
class GroundedPairGenerationTest {

    private static Map<String, String> meta(String source) {
        Map<String, String> m = new HashMap<>();
        m.put("sourceFile", source);
        return m;
    }

    private static final List<String> DOCS = List.of(
            "Le péage de sortie est conforme.",
            "La viabilité hivernale démarre le 15 novembre.",
            "Le péage accepte les paiements sans contact.",
            "Les bandes d'arrêt d'urgence mesurent 3 mètres.");
    private static final List<Map<String, String>> METAS = List.of(
            meta("peage.pdf"), meta("hiver.pdf"), meta("peage.pdf"), meta("bau.pdf"));

    @Test
    @DisplayName("les distracteurs viennent d'un AUTRE document quand c'est possible")
    void distractorsPreferAnotherDocument() {
        // Un chunk voisin du même fichier a de bonnes chances de contenir lui aussi la réponse :
        // il deviendrait un second passage correct, et la citation attendue serait fausse sans
        // que rien ne le signale.
        List<RagPromptFormat.Passage> picked =
                DatasetGeneratorService.distractorsFor(0, DOCS, METAS, 2);

        assertThat(picked).hasSize(2);
        assertThat(picked).extracting(RagPromptFormat.Passage::sourceFile)
                .doesNotContain("peage.pdf");
    }

    @Test
    @DisplayName("un corpus mono-document donne quand même des distracteurs")
    void singleDocumentCorpusStillProducesDistractors() {
        // Mieux vaut un distracteur imparfait que pas de contexte du tout : sans repli, un corpus
        // d'un seul fichier n'aurait produit aucune paire ancrée — c'est-à-dire aucun bénéfice
        // là où le produit est le plus souvent déployé.
        List<Map<String, String>> sameDoc = List.of(meta("a.pdf"), meta("a.pdf"), meta("a.pdf"));

        assertThat(DatasetGeneratorService.distractorsFor(0, DOCS.subList(0, 3), sameDoc, 2))
                .hasSize(2);
    }

    @Test
    @DisplayName("un corpus d'un seul chunk ne produit pas de distracteur")
    void lonelyChunkHasNoDistractor() {
        assertThat(DatasetGeneratorService.distractorsFor(
                0, List.of("seul"), List.of(meta("a.pdf")), 2)).isEmpty();
    }

    @Test
    @DisplayName("la position du vrai passage TOURNE d'un chunk à l'autre")
    void truePassagePositionRotates() {
        // Figée en [1], le modèle apprendrait à citer la première position quoi qu'elle
        // contienne — un réflexe, pas une lecture.
        TrainingPair source = TrainingPair.of("Question ?", "Réponse.", "s.pdf", "qa",
                "question_answer", 0.9, "technique");
        List<RagPromptFormat.Passage> distractors = List.of(
                new RagPromptFormat.Passage("x.pdf", "Distracteur A"),
                new RagPromptFormat.Passage("y.pdf", "Distracteur B"));

        List<String> citations = new ArrayList<>();
        for (int seed = 0; seed < 3; seed++) {
            TrainingPair twin = DatasetGeneratorService.groundedVariant(
                    source, "Le vrai passage.", "s.pdf", distractors, seed);
            citations.add(twin.conversations().get(2).content());
        }

        assertThat(citations).containsExactly("Réponse [1].", "Réponse [2].", "Réponse [3].");
    }

    @Test
    @DisplayName("la citation désigne la position réelle du vrai passage dans le contexte")
    void citationPointsAtTheTruePassage() {
        TrainingPair source = TrainingPair.of("Question ?", "Réponse.", "s.pdf", "qa",
                "question_answer", 0.9, null);
        List<RagPromptFormat.Passage> distractors = List.of(
                new RagPromptFormat.Passage("x.pdf", "Distracteur A"),
                new RagPromptFormat.Passage("y.pdf", "Distracteur B"));

        TrainingPair twin = DatasetGeneratorService.groundedVariant(
                source, "Le vrai passage.", "s.pdf", distractors, 1);

        // Le vrai passage est en deuxième position, et c'est [2] que la réponse cite. Un décalage
        // ici enseignerait au modèle à citer un passage qui ne justifie pas sa réponse — soit
        // exactement l'hallucination de source que les consignes cherchent à empêcher.
        String system = twin.conversations().get(0).content();
        assertThat(system).contains("[2] (Source: s.pdf)\nLe vrai passage.");
        assertThat(twin.conversations().get(2).content()).isEqualTo("Réponse [2].");
    }

    @Test
    @DisplayName("le prompt système du jumeau est celui du service, pas la persona seule")
    void groundedTwinUsesTheServingPrompt() {
        TrainingPair source = TrainingPair.of("Question ?", "Réponse.", "s.pdf", "qa",
                "question_answer", 0.9, null);

        TrainingPair twin = DatasetGeneratorService.groundedVariant(
                source, "Passage.", "s.pdf", List.of(), 0);

        assertThat(twin.conversations().get(0).content())
                .contains("cite tes sources en insérant le numéro")
                .contains("=== CONTEXTE ===");
        // Le type est distinct pour que le dataset reste analysable, la catégorie est conservée
        // pour que les filtres d'exclusion SFT continuent de mordre.
        assertThat(twin.metadata().type()).isEqualTo("question_answer_grounded");
        assertThat(twin.metadata().category()).isEqualTo("qa");
    }

    @Test
    @DisplayName("le refus ancré présente un contexte qui NE contient pas la réponse")
    void groundedRefusalExcludesTheSourceChunk() {
        // C'est le seul exemple qui entraîne « si le contexte ne contient pas l'information,
        // dis-le clairement » — consigne donnée à chaque requête servie, et que rien
        // n'enseignait. Y glisser le vrai passage enseignerait l'inverse.
        TrainingPair refusal = TrainingPair.of(
                "Quel est le tarif de nuit ?",
                "Je ne dispose pas de cette information dans les documents fournis.",
                "peage.pdf", "negative", "refusal", 0.9, null);
        List<RagPromptFormat.Passage> distractors = List.of(
                new RagPromptFormat.Passage("hiver.pdf", "La viabilité hivernale démarre le 15 novembre."));

        TrainingPair grounded = DatasetGeneratorService.groundedRefusal(refusal, distractors);

        assertThat(grounded.conversations().get(0).content())
                .contains("[1] (Source: hiver.pdf)")
                .doesNotContain("tarif de nuit");
        // La réponse reste le refus, SANS citation : citer un passage tout en disant qu'on n'a
        // pas l'information serait contradictoire.
        assertThat(grounded.conversations().get(2).content())
                .isEqualTo("Je ne dispose pas de cette information dans les documents fournis.");
        assertThat(grounded.metadata().type()).isEqualTo("refusal_grounded");
    }

    @Test
    @DisplayName("une réponse qui cite déjà n'est pas re-citée")
    void answerThatAlreadyCitesIsLeftAlone() {
        // Ajouter un second numéro que le contexte ne justifie pas est précisément ce que les
        // consignes de service interdisent (« n'invente jamais de numéro »).
        assertThat(DatasetGeneratorService.withCitation("La valeur est 512 [3].", 1))
                .isEqualTo("La valeur est 512 [3].");
    }

    @Test
    @DisplayName("la citation se place avant la ponctuation finale, comme dans l'exemple donné")
    void citationGoesBeforeTheFinalPunctuation() {
        assertThat(DatasetGeneratorService.withCitation("La valeur est 512.", 3))
                .isEqualTo("La valeur est 512 [3].");
        assertThat(DatasetGeneratorService.withCitation("Est-ce conforme ?", 2))
                .isEqualTo("Est-ce conforme [2] ?");
        assertThat(DatasetGeneratorService.withCitation("Sans ponctuation", 1))
                .isEqualTo("Sans ponctuation [1]");
    }

    @Test
    @DisplayName("une paire mal formée ne produit pas de jumeau plutôt qu'un jumeau bancal")
    void malformedPairYieldsNoTwin() {
        assertThat(DatasetGeneratorService.groundedVariant(null, "x", "s.pdf", List.of(), 0)).isNull();
        TrainingPair truncated = new TrainingPair(
                List.of(new TrainingPair.Message("system", "s")),
                new TrainingPair.Metadata("s.pdf", "qa", "question_answer", 0.9, null));
        assertThat(DatasetGeneratorService.groundedVariant(truncated, "x", "s.pdf", List.of(), 0)).isNull();
    }
}
