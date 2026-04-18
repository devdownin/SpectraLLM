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
     * Génère une réponse en streaming, token par token.
     *
     * <p>Retourne un {@code Flux<String>} qui émet chaque token dès qu'il est
     * disponible. L'implémentation par défaut délègue à {@link #chat} et émet
     * la réponse complète en un seul élément — les providers sans streaming natif
     * (Ollama) utilisent ce fallback.
     *
     * <p>L'implémentation llama-cpp utilise {@code "stream": true} sur
     * {@code /v1/chat/completions} et émet chaque delta SSE individuellement.
     */
    default Flux<String> chatStream(String systemPrompt, String userMessage) {
        return Flux.just(chat(systemPrompt, userMessage));
    }

    List<Map<String, Object>> listModels();

    void createModel(String name, String from, String system, Map<String, Object> params);

    void pullModel(String name);

    boolean hasModel(String name);

    String getDefaultModel();
}
