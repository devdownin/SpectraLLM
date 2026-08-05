package fr.spectra.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Forme du prompt servi en RAG, et <b>seule</b> définition de celle-ci.
 *
 * <p>Ces tests existent parce que cette mise en forme est désormais un contrat entre deux usages
 * qui ne se rencontrent jamais à l'exécution : ce que {@code RagService} envoie au modèle, et ce
 * sur quoi le dataset de fine-tuning l'entraîne. Le constat F14 est exactement l'écart entre les
 * deux — le modèle apprenait à répondre sans contexte ni citation, puis on lui demandait les
 * deux.
 */
class RagPromptFormatTest {

    @Test
    @DisplayName("les passages sont numérotés à partir de 1, dans l'ordre donné")
    void passagesAreNumberedFromOne() {
        String block = RagPromptFormat.contextBlock(List.of(
                new RagPromptFormat.Passage("a.pdf", "Premier"),
                new RagPromptFormat.Passage("b.pdf", "Second")));

        // La numérotation est ce à quoi les citations [n] de la réponse se résolvent : la
        // décaler rendrait toute citation fausse sans qu'aucune erreur ne soit levée.
        assertThat(block).isEqualTo("""
                [1] (Source: a.pdf)
                Premier

                [2] (Source: b.pdf)
                Second

                """);
    }

    @Test
    @DisplayName("une source inconnue est nommée, jamais laissée nulle")
    void unknownSourceIsNamed() {
        assertThat(RagPromptFormat.passage(1, null, "texte")).contains("(Source: inconnu)");
    }

    @Test
    @DisplayName("un contexte vide vaut null — le service bascule alors en mode direct")
    void emptyContextIsNull() {
        // Distinct d'un bloc vide : « pas de contexte » et « contexte présent mais sans contenu »
        // n'appellent pas le même prompt.
        assertThat(RagPromptFormat.contextBlock(List.of())).isNull();
        assertThat(RagPromptFormat.contextBlock(null)).isNull();
    }

    @Test
    @DisplayName("le prompt système porte la persona, puis les consignes, puis le contexte")
    void systemPromptComposesPersonaThenInstructions() {
        String prompt = RagPromptFormat.systemPrompt("Tu es un juriste.", "[1] (Source: x)\nTexte");

        assertThat(prompt).startsWith("Tu es un juriste.\n");
        // Les consignes de citation sont ce que le fine-tuning doit apprendre à honorer : leur
        // disparition du prompt d'entraînement est précisément le défaut F14.
        assertThat(prompt).contains("cite tes sources en insérant le numéro");
        assertThat(prompt).contains("Si le contexte ne contient pas l'information demandée");
        assertThat(prompt).contains("=== CONTEXTE ===\n[1] (Source: x)\nTexte\n=== FIN DU CONTEXTE ===");
    }

    @Test
    @DisplayName("une paire ancrée porte EXACTEMENT le prompt système du service")
    void groundedPairCarriesTheServingPrompt() {
        List<RagPromptFormat.Passage> passages = List.of(
                new RagPromptFormat.Passage("peage.json", "Le péage de sortie est conforme."));
        String contextBlock = RagPromptFormat.contextBlock(passages);

        TrainingPair pair = TrainingPair.grounded(
                "Le péage est-il conforme ?", "Oui, il est conforme [1].", contextBlock,
                "peage.json", "qa", "question_answer_grounded", 0.9, "technique");

        // Égalité stricte, et non « contient » : c'est tout l'objet du correctif. Un préfixe, un
        // saut de ligne ou une consigne en plus d'un côté suffisent à recréer l'écart de
        // distribution que ce travail supprime.
        assertThat(pair.conversations().get(0).content())
                .isEqualTo(RagPromptFormat.systemPrompt(AssistantPersona.SYSTEM_PROMPT, contextBlock));
        // La question reste NUE : au service, elle arrive telle quelle en message utilisateur.
        assertThat(pair.conversations().get(1).content()).isEqualTo("Le péage est-il conforme ?");
    }

    @Test
    @DisplayName("une paire ordinaire n'a toujours que la persona — les deux formes coexistent")
    void plainPairKeepsThePersonaOnlyPrompt() {
        // Le dataset doit contenir les DEUX formes : le mode direct (sans retrieval) existe
        // toujours, et n'entraîner que la forme ancrée le dégraderait à son tour.
        TrainingPair plain = TrainingPair.of("Question ?", "Réponse.", "s.pdf", "qa", "question_answer", 0.9);

        assertThat(plain.conversations().get(0).content()).isEqualTo(AssistantPersona.SYSTEM_PROMPT);
    }
}
