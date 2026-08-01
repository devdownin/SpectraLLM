package fr.spectra.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le juge LLM : un barème, un appel contraint, une sémantique d'échec.
 *
 * <p>Ces trois propriétés étaient auparavant divergentes entre les deux services qui
 * notaient des réponses. Les cas ci-dessous fixent chacune d'elles.</p>
 */
class LlmJudgeTest {

    private LlmChatClient chat;
    private LlmJudge judge;

    @BeforeEach
    void setUp() {
        chat = mock(LlmChatClient.class);
        judge = new LlmJudge(chat);
    }

    @Test
    void scoreUsesConstrainedDecoding_notFreeGeneration() {
        // Le benchmark qualité notait en génération libre : son juge pouvait répondre en
        // prose. Le décodage contraint rend ce cas structurellement impossible.
        when(chat.chatJson(anyString(), anyString()))
                .thenReturn("{\"score\": 8, \"justification\": \"correcte\"}");

        judge.score("q", "ref", "réponse");

        verify(chat).chatJson(anyString(), anyString());
        verify(chat, never()).chat(anyString(), anyString());
    }

    @Test
    void scorePromptCarriesAnExplicitRubric() {
        // Deux barèmes donnaient deux « scores sur 10 » incomparables. Celui-ci est explicite
        // et ses trois critères somment à 10, ce qui rend la note décomposable.
        assertThat(LlmJudge.SCORE_PROMPT)
                .contains("Exactitude (0-4)")
                .contains("Complétude (0-3)")
                .contains("Clarté (0-3)");
    }

    @Test
    void noVerdict_yieldsEmpty_ratherThanANeutralScore() {
        // Le cœur du correctif : une note fabriquée entrait dans la moyenne et la tirait
        // vers le milieu à chaque juge instable. Un jugement absent doit rester absent.
        when(chat.chatJson(anyString(), anyString())).thenReturn("je ne sais pas trancher");

        assertThat(judge.score("q", "ref", "réponse")).isEmpty();
    }

    @Test
    void clientFailure_yieldsEmpty_ratherThanPropagating() {
        when(chat.chatJson(anyString(), anyString())).thenThrow(new RuntimeException("serveur HS"));

        assertThat(judge.score("q", "ref", "réponse")).isEmpty();
    }

    @Test
    void scoreOutOfRange_isClampedToTheScale() {
        // Le décodage contraint garantit un JSON valide, pas une valeur dans l'intervalle.
        when(chat.chatJson(anyString(), anyString())).thenReturn("{\"score\": 42}");

        assertThat(judge.score("q", "ref", "r")).get()
                .extracting(LlmJudge.Verdict::score).isEqualTo(10.0);
    }

    @Test
    void refusalIsADistinctQuestion_notAScoreOfZero() {
        // Une question sans réponse dans le corpus ne se note pas en exactitude : on
        // constate une abstention. Prompt distinct parce que la question l'est.
        when(chat.chatJson(anyString(), anyString()))
                .thenReturn("{\"refused\": true, \"justification\": \"s'abstient\"}");

        assertThat(judge.refusal("q", "je ne sais pas")).get()
                .extracting(LlmJudge.RefusalVerdict::refused).isEqualTo(true);
    }

    @Test
    void verdictSurroundedByProse_isStillRead() {
        // Le décodage contraint supprime ce cas sur llama.cpp ; un provider sans cette
        // capacité reste fonctionnel, d'où le parsing défensif conservé.
        when(chat.chatJson(anyString(), anyString()))
                .thenReturn("Voici mon verdict : {\"score\": 7} — j'espère que cela convient.");

        assertThat(judge.score("q", "ref", "r")).get()
                .extracting(LlmJudge.Verdict::score).isEqualTo(7.0);
    }

    @Test
    void extractJsonObject_stopsAtTheBalancedBrace() {
        assertThat(LlmJudge.extractJsonObject("{\"a\":{\"b\":1}} puis une accolade } isolée"))
                .isEqualTo("{\"a\":{\"b\":1}}");
        assertThat(LlmJudge.extractJsonObject("{\"j\":\"accolade } dans le texte\"}"))
                .isEqualTo("{\"j\":\"accolade } dans le texte\"}");
        assertThat(LlmJudge.extractJsonObject("aucun objet ici")).isNull();
    }
}
