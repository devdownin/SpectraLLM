package fr.spectra.service;

import fr.spectra.dto.ConversationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Conversational RAG — gestion du contexte multi-tours (I5).
 *
 * <p>Deux responsabilités :
 * <ol>
 *   <li>Reformuler la question courante en question autonome (standalone question) à partir de
 *       l'historique, afin d'améliorer la qualité du retrieval vectoriel.</li>
 *   <li>Construire le message utilisateur enrichi de l'historique pour la génération finale,
 *       afin que le LLM dispose du fil de la conversation.</li>
 * </ol>
 *
 * <p>Activé via {@code SPECTRA_CONVERSATIONAL_RAG_ENABLED=true}.
 */
@Service
@ConditionalOnProperty(prefix = "spectra.conversational-rag", name = "enabled", havingValue = "true")
public class ConversationalRagService {

    private static final Logger log = LoggerFactory.getLogger(ConversationalRagService.class);

    private static final String CONTEXTUALIZE_SYSTEM =
            "Tu es un assistant de reformulation. Ton seul rôle est de reformuler une question.";

    private static final String CONTEXTUALIZE_PROMPT = """
            Étant donné l'historique de conversation ci-dessous et une nouvelle question, \
            reformule la question comme une question autonome et complète, compréhensible sans l'historique.
            Si la question est déjà autonome ou ne fait aucune référence à l'historique, renvoie-la telle quelle.
            Ne réponds PAS à la question. Renvoie UNIQUEMENT la question reformulée, sans commentaire.

            === HISTORIQUE ===
            %s
            === FIN DE L'HISTORIQUE ===

            Question originale : %s
            Question reformulée :""";

    private final LlmChatClient llmClient;

    public ConversationalRagService(LlmChatClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * Reformule {@code question} en question autonome en s'appuyant sur {@code history}.
     * Retourne la question originale si l'historique est vide.
     */
    public String contextualizeQuestion(String question, List<ConversationMessage> history) {
        if (history == null || history.isEmpty()) return question;

        String historyText = formatHistory(history);
        String prompt = String.format(CONTEXTUALIZE_PROMPT, historyText, question);
        String standalone = llmClient.chat(CONTEXTUALIZE_SYSTEM, prompt).trim();

        // Sanity-check: if the model returns an empty string, fall back to the original.
        if (standalone.isBlank()) {
            log.warn("Conversational RAG : reformulation vide, fallback sur la question originale");
            return question;
        }
        if (!standalone.equals(question)) {
            log.info("Conversational RAG : «{}» → «{}»", question, standalone);
        }
        return standalone;
    }

    /**
     * Construit le message utilisateur en préfixant la question courante avec l'historique.
     * Le LLM de génération dispose ainsi du fil de la conversation.
     */
    public String buildUserMessageWithHistory(String question, List<ConversationMessage> history) {
        if (history == null || history.isEmpty()) return question;
        return "=== HISTORIQUE DE CONVERSATION ===\n"
                + formatHistory(history)
                + "\n=== FIN DE L'HISTORIQUE ===\n\n"
                + "Question actuelle : " + question;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String formatHistory(List<ConversationMessage> history) {
        return history.stream()
                .map(m -> (m.role().equalsIgnoreCase("user") ? "Utilisateur" : "Assistant")
                        + " : " + m.content())
                .collect(Collectors.joining("\n"));
    }
}
