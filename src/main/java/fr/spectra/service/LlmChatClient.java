package fr.spectra.service;

import fr.spectra.dto.ServiceStatus;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Abstraction du provider de chat LLM.
 */
public interface LlmChatClient {

    void setActiveModel(String model);

    String getActiveModel();

    ServiceStatus checkHealth();

    /**
     * Génère une réponse complète (bloquant jusqu'à la fin de la génération).
     * Protégé par un circuit breaker dans les implémentations llama-cpp.
     */
    String chat(String systemPrompt, String userMessage);

    /**
     * Réponse enrichie des statistiques de génération renvoyées par le serveur.
     *
     * @param content          texte généré
     * @param completionTokens nombre de tokens générés (0 si inconnu)
     * @param tokensPerSecond   débit de génération mesuré par le serveur (0 si inconnu)
     */
    record ChatResult(String content, int completionTokens, double tokensPerSecond) {}

    /**
     * Variante de {@link #chat(String, String)} qui remonte aussi les statistiques de
     * génération (tokens, débit) lorsque le provider les expose. L'implémentation par
     * défaut délègue à {@link #chat} sans statistiques (l'appelant retombe alors sur une
     * estimation).
     */
    default ChatResult chatWithStats(String systemPrompt, String userMessage) {
        return new ChatResult(chat(systemPrompt, userMessage), 0, 0.0);
    }

    /**
     * Génère une réponse complète avec paramètres de génération explicites (temperature, top_p).
     * L'implémentation par défaut ignore les paramètres et délègue à {@link #chat(String, String)}.
     */
    default String chat(String systemPrompt, String userMessage, float temperature, float topP) {
        return chat(systemPrompt, userMessage);
    }

    /**
     * Génère une réponse en streaming, token par token.
     * L'implémentation par défaut délègue à {@link #chat} et émet la réponse complète en un seul élément.
     */
    default Flux<String> chatStream(String systemPrompt, String userMessage) {
        return Flux.just(chat(systemPrompt, userMessage));
    }

    /**
     * Streaming avec paramètres de génération explicites (temperature, top_p).
     * L'implémentation par défaut ignore les paramètres et délègue à {@link #chatStream(String, String)}.
     */
    default Flux<String> chatStream(String systemPrompt, String userMessage, float temperature, float topP) {
        return chatStream(systemPrompt, userMessage);
    }

    List<Map<String, Object>> listModels();

    void createModel(String name, String from, String system, Map<String, Object> params);

    void pullModel(String name);

    boolean hasModel(String name);

    String getDefaultModel();
}
