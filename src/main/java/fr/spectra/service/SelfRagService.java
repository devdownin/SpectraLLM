package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Self-RAG — auto-évaluation de la qualité de récupération et de génération (I8).
 *
 * <p>Implémente une version simplifiée du cadre Self-RAG (Asai et al., 2023) avec
 * trois tokens de réflexion :</p>
 * <ul>
 *   <li><b>ISREL</b> (is relevant) — les chunks récupérés sont-ils pertinents pour la question ?</li>
 *   <li><b>ISSUP</b> (is supported) — la réponse générée est-elle fondée sur les chunks ?</li>
 *   <li><b>ISUSE</b> (is useful) — la réponse est-elle utile et complète ?</li>
 * </ul>
 *
 * <p>Si la réponse initiale obtient un score insuffisant (ISUSE = NOT_USEFUL ou
 * ISSUP = NO_SUPPORT), une deuxième tentative de génération est effectuée avec
 * un prompt plus directif. Le nombre de tentatives est limité par
 * {@code spectra.self-rag.max-reflection-iterations} (défaut : 1).</p>
 *
 * <p>Activé via {@code SPECTRA_SELF_RAG_ENABLED=true}.</p>
 */
@Service
@ConditionalOnProperty(prefix = "spectra.self-rag", name = "enabled", havingValue = "true")
public class SelfRagService {

    private static final Logger log = LoggerFactory.getLogger(SelfRagService.class);

    // ── Tokens de réflexion ────────────────────────────────────────────────

    public enum IsRel  { RELEVANT, IRRELEVANT }
    public enum IsSup  { FULLY_SUPPORTED, PARTIALLY_SUPPORTED, NO_SUPPORT }
    public enum IsUse  { USEFUL, NOT_USEFUL }

    // ── Prompts ────────────────────────────────────────────────────────────

    private static final String REFLECT_SYSTEM =
            "Tu es un évaluateur de qualité de réponses RAG. Réponds uniquement avec les tokens demandés.";

    private static final String REFLECT_PROMPT = """
            Évalue la réponse générée ci-dessous par rapport à la question et aux documents sources.

            Question : %s

            === DOCUMENTS SOURCES ===
            %s
            === FIN DES DOCUMENTS ===

            === RÉPONSE GÉNÉRÉE ===
            %s
            === FIN DE LA RÉPONSE ===

            Réponds avec exactement ce format (une ligne par token) :
            ISREL: [RELEVANT|IRRELEVANT]
            ISSUP: [FULLY_SUPPORTED|PARTIALLY_SUPPORTED|NO_SUPPORT]
            ISUSE: [USEFUL|NOT_USEFUL]""";

    private static final String REFINE_SYSTEM_ADDENDUM = """

            IMPORTANT : ta réponse précédente était jugée insuffisante. \
            Sois plus précis, plus complet et appuie-toi STRICTEMENT sur les sources fournies.""";

    private static final Pattern ISREL = Pattern.compile("ISREL:\\s*(RELEVANT|IRRELEVANT)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISSUP = Pattern.compile("ISSUP:\\s*(FULLY_SUPPORTED|PARTIALLY_SUPPORTED|NO_SUPPORT)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISUSE = Pattern.compile("ISUSE:\\s*(USEFUL|NOT_USEFUL)", Pattern.CASE_INSENSITIVE);

    private static final int CONTEXT_PREVIEW_CHARS = 300;

    private final LlmChatClient llmClient;
    private final SpectraProperties props;

    public SelfRagService(LlmChatClient llmClient, SpectraProperties props) {
        this.llmClient = llmClient;
        this.props = props;
    }

    /**
     * Génère une réponse et l'évalue via les tokens de réflexion.
     * En cas de score insuffisant, relance la génération avec un prompt renforcé.
     *
     * @param question    question utilisateur
     * @param chunks      chunks de contexte sélectionnés
     * @param systemPrompt prompt système avec contexte RAG déjà injecté
     * @param userMessage  message utilisateur (avec historique conversationnel si applicable)
     * @return {@link SelfRagResult} contenant la meilleure réponse et les scores de réflexion
     */
    public SelfRagResult reflect(String question,
                                  List<String> chunks,
                                  String systemPrompt,
                                  String userMessage) {

        int maxIterations = props.selfRag() != null
                ? props.selfRag().effectiveMaxReflectionIterations() : 1;

        String answer = llmClient.chat(systemPrompt, userMessage);
        ReflectionScores scores = evaluateAnswer(question, chunks, answer);
        log.info("Self-RAG évaluation initiale — ISREL={}, ISSUP={}, ISUSE={}",
                scores.isRel(), scores.isSup(), scores.isUse());

        if (needsRefinement(scores) && maxIterations > 0) {
            log.info("Self-RAG : qualité insuffisante, tentative de raffinement");
            String refinedSystemPrompt = systemPrompt + REFINE_SYSTEM_ADDENDUM;
            answer = llmClient.chat(refinedSystemPrompt, userMessage);
            scores = evaluateAnswer(question, chunks, answer);
            log.info("Self-RAG après raffinement — ISREL={}, ISSUP={}, ISUSE={}",
                    scores.isRel(), scores.isSup(), scores.isUse());
        }

        return new SelfRagResult(answer, scores, needsRefinement(scores) ? 1 : 0);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ReflectionScores evaluateAnswer(String question, List<String> chunks, String answer) {
        String contextPreview = chunks.stream()
                .limit(3)
                .map(c -> c.length() > CONTEXT_PREVIEW_CHARS ? c.substring(0, CONTEXT_PREVIEW_CHARS) + "…" : c)
                .reduce("", (a, b) -> a + b + "\n---\n");

        String prompt = String.format(REFLECT_PROMPT, question, contextPreview, answer);
        String response;
        try {
            response = llmClient.chat(REFLECT_SYSTEM, prompt);
        } catch (Exception e) {
            log.warn("Self-RAG : erreur d'évaluation, scores par défaut — {}", e.getMessage());
            return new ReflectionScores(IsRel.RELEVANT, IsSup.FULLY_SUPPORTED, IsUse.USEFUL);
        }
        return parseScores(response);
    }

    private ReflectionScores parseScores(String response) {
        IsRel isRel = IsRel.RELEVANT;
        IsSup isSup = IsSup.PARTIALLY_SUPPORTED;
        IsUse isUse = IsUse.USEFUL;

        var mRel = ISREL.matcher(response);
        if (mRel.find()) isRel = IsRel.valueOf(mRel.group(1).toUpperCase());

        var mSup = ISSUP.matcher(response);
        if (mSup.find()) isSup = IsSup.valueOf(mSup.group(1).toUpperCase().replace(" ", "_"));

        var mUse = ISUSE.matcher(response);
        if (mUse.find()) isUse = IsUse.valueOf(mUse.group(1).toUpperCase().replace(" ", "_"));

        return new ReflectionScores(isRel, isSup, isUse);
    }

    private boolean needsRefinement(ReflectionScores scores) {
        return scores.isUse() == IsUse.NOT_USEFUL || scores.isSup() == IsSup.NO_SUPPORT;
    }

    // ── DTOs internes ─────────────────────────────────────────────────────────

    public record ReflectionScores(IsRel isRel, IsSup isSup, IsUse isUse) {}

    public record SelfRagResult(
            String answer,
            ReflectionScores scores,
            int reflectionIterations
    ) {
        public boolean reflectionApplied() { return reflectionIterations > 0; }
    }
}
