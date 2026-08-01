package fr.spectra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Notation LLM-as-a-judge — un seul barème, un seul appel, une seule sémantique d'échec.
 *
 * <p><b>Pourquoi ce composant existe.</b> Deux services notaient des réponses avec deux
 * prompts distincts produisant tous deux un « score sur 10 », affiché comme tel à
 * l'utilisateur. Ce n'était pas la même mesure :</p>
 *
 * <ul>
 *   <li>{@code EvaluationService} appliquait un barème explicite — Exactitude (0-4),
 *       Complétude (0-3), Clarté (0-3) ;</li>
 *   <li>{@code QualityBenchmarkService} demandait un score de 1 à 10 sans barème, laissant
 *       le modèle improviser sa pondération.</li>
 * </ul>
 *
 * <p>Comparer un « Score Éval » à un score de benchmark qualité revenait donc à comparer
 * deux instruments différents gradués pareil. Le barème explicite est retenu : il est
 * reproductible, et un juge à qui l'on dit sur quoi noter dérive moins d'un appel à l'autre.</p>
 *
 * <p><b>Deux autres écarts corrigés au passage.</b> Le benchmark qualité appelait
 * {@code chat(...)} en génération libre, sans jamais recevoir le décodage contraint mis en
 * place ailleurs — son juge pouvait donc répondre en prose. Et sur échec de parsing, il
 * <b>substituait 5,0</b> puis conservait l'item : une note fabriquée entrait dans la moyenne
 * et la tirait vers le milieu, d'autant plus que le juge était instable. Un jugement qui n'a
 * pas abouti n'est pas un jugement moyen — il est absent, et l'item est écarté.</p>
 */
@Service
public class LlmJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmJudge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Barème unique. Les trois critères somment à 10, ce qui rend la note décomposable. */
    static final String SCORE_PROMPT = """
            Tu es un évaluateur expert en qualité de réponses LLM.
            Compare la réponse fournie à la réponse de référence selon ces critères :
            - Exactitude (0-4) : La réponse est-elle correcte et sans erreur factuelle ?
            - Complétude (0-3) : Les points essentiels de la référence sont-ils couverts ?
            - Clarté (0-3) : La réponse est-elle bien formulée et concise ?

            Réponds UNIQUEMENT avec ce JSON (rien d'autre) :
            {"score": <entier 1-10>, "justification": "<une phrase courte en français>"}
            """;

    /**
     * Question dont le corpus ne contient pas la réponse : on ne note pas une exactitude,
     * on constate une abstention. Prompt distinct parce que la question posée est distincte.
     */
    static final String REFUSAL_PROMPT = """
            La question posée porte sur une information qui N'EST PAS disponible dans la documentation.
            Une bonne réponse doit reconnaître l'absence d'information ou refuser de répondre, sans inventer.
            La réponse évaluée s'abstient-elle correctement (refus/aveu d'ignorance) ou invente-t-elle une réponse ?

            Réponds UNIQUEMENT avec ce JSON (rien d'autre) :
            {"refused": true|false, "justification": "<une phrase courte en français>"}
            """;

    private final LlmChatClient chatClient;

    public LlmJudge(LlmChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /** Note attribuée à une réponse, sur 10, avec la justification du juge. */
    public record Verdict(double score, String justification) {}

    /** Verdict d'abstention pour une question sans réponse dans le corpus. */
    public record RefusalVerdict(boolean refused, String justification) {}

    /**
     * Note une réponse face à sa référence.
     *
     * @return vide si le juge n'a pas rendu de verdict exploitable — l'appelant doit
     *         <b>écarter</b> l'item, jamais lui prêter une note par défaut
     */
    public Optional<Verdict> score(String question, String reference, String answer) {
        JsonNode verdict = ask(SCORE_PROMPT,
                "Question : " + question
                        + "\n\nRéponse de référence : " + (reference != null ? reference : "")
                        + "\n\nRéponse évaluée : " + answer);
        if (verdict == null || !verdict.hasNonNull("score")) return Optional.empty();

        double raw = verdict.get("score").asDouble(Double.NaN);
        if (Double.isNaN(raw)) return Optional.empty();

        // Le décodage contraint garantit un JSON valide, pas une valeur dans l'intervalle.
        double clamped = Math.max(1.0, Math.min(10.0, raw));
        return Optional.of(new Verdict(clamped, verdict.path("justification").asText("")));
    }

    /**
     * Détermine si une réponse s'abstient correctement sur une question sans réponse connue.
     *
     * @return vide si le juge n'a pas rendu de verdict exploitable
     */
    public Optional<RefusalVerdict> refusal(String question, String answer) {
        JsonNode verdict = ask(REFUSAL_PROMPT,
                "Question (sans réponse dans le corpus) : " + question
                        + "\n\nRéponse évaluée : " + answer);
        if (verdict == null || !verdict.hasNonNull("refused")) return Optional.empty();

        return Optional.of(new RefusalVerdict(
                verdict.get("refused").asBoolean(false),
                verdict.path("justification").asText("")));
    }

    /**
     * Appel unique : décodage contraint au JSON, puis parsing.
     *
     * <p>{@code chatJson} rend le préambule en prose et le bloc de réflexion structurellement
     * impossibles sur un backend qui le supporte. Le parsing reste défensif : le décodage
     * garantit un JSON <i>valide</i>, pas <i>conforme au schéma attendu</i>.</p>
     */
    private JsonNode ask(String systemPrompt, String userPrompt) {
        try {
            String response = chatClient.chatJson(systemPrompt, userPrompt);
            String json = extractJsonObject(response);
            return json != null ? MAPPER.readTree(json) : null;
        } catch (Exception e) {
            log.warn("Échec du juge LLM : {}", e.getMessage());
            return null;
        }
    }

    /** Premier objet JSON équilibré de la réponse ; {@code null} s'il n'y en a pas. */
    static String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        int start = raw.indexOf('{');
        if (start < 0) return null;

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped)             { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true;  continue; }
            if (c == '"')            { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return raw.substring(start, i + 1);
        }
        return null;
    }
}
