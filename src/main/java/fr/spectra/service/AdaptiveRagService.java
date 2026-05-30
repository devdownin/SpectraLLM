package fr.spectra.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Adaptive RAG — routeur de stratégie basé sur la complexité de la requête (I7).
 *
 * <p>Classifie chaque question en l'une des trois stratégies :</p>
 * <ul>
 *   <li>{@link RagStrategy#DIRECT} — la question peut être résolue sans recherche documentaire
 *       (conversation générale, salutation, calcul simple…). Le LLM répond directement.</li>
 *   <li>{@link RagStrategy#STANDARD} — question factuelle nécessitant une recherche vectorielle
 *       classique, sans itération agentique. Chemin par défaut.</li>
 *   <li>{@link RagStrategy#AGENTIC} — question complexe, multi-étapes ou nécessitant un
 *       raisonnement avancé. Délègue au pipeline {@link AgenticRagService} si disponible.</li>
 * </ul>
 *
 * <p>Activé via {@code SPECTRA_ADAPTIVE_RAG_ENABLED=true}.
 * Si {@link AgenticRagService} n'est pas activé et que la classification renvoie
 * {@code AGENTIC}, le routeur dégrade gracieusement vers {@code STANDARD}.</p>
 */
@Service
@ConditionalOnProperty(prefix = "spectra.adaptive-rag", name = "enabled", havingValue = "true")
public class AdaptiveRagService {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveRagService.class);

    public enum RagStrategy { DIRECT, STANDARD, AGENTIC }

    private static final String CLASSIFY_SYSTEM =
            "Tu es un classificateur de requêtes. Réponds avec un seul mot parmi : DIRECT, STANDARD, AGENTIC.";

    private static final String CLASSIFY_PROMPT = """
            Classifie la complexité de la question suivante pour déterminer la meilleure stratégie de réponse.

            Réponds avec UN SEUL mot :
            - DIRECT   : question générale, salutation ou question ne nécessitant pas de documents spécifiques
            - STANDARD : question factuelle nécessitant une simple recherche documentaire
            - AGENTIC  : question complexe, multi-étapes, comparative ou nécessitant un raisonnement avancé

            Question : %s

            Stratégie :""";

    private final LlmChatClient llmClient;

    public AdaptiveRagService(LlmChatClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * Classifie la question et retourne la {@link RagStrategy} recommandée.
     * En cas d'erreur ou de réponse inattendue, retourne {@code STANDARD} par défaut.
     */
    public RagStrategy classifyQuery(String question) {
        String prompt = String.format(CLASSIFY_PROMPT, question);
        String raw;
        try {
            raw = llmClient.chat(CLASSIFY_SYSTEM, prompt).trim().toUpperCase();
        } catch (Exception e) {
            log.warn("Adaptive RAG : erreur de classification, fallback STANDARD — {}", e.getMessage());
            return RagStrategy.STANDARD;
        }

        RagStrategy strategy = switch (raw) {
            case "DIRECT"   -> RagStrategy.DIRECT;
            case "AGENTIC"  -> RagStrategy.AGENTIC;
            default         -> RagStrategy.STANDARD;
        };
        log.info("Adaptive RAG : «{}» → {}", question, strategy);
        return strategy;
    }
}
