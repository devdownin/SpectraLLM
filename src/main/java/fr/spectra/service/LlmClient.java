package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.ServiceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Client pour le serveur LLM (llama.cpp, API OpenAI-compatible).
 */
@Service
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final SpectraProperties.LlmProperties props;
    private final Duration generateTimeout;

    public LlmClient(@Qualifier("llmWebClient") WebClient webClient,
                      SpectraProperties properties) {
        this.webClient = webClient;
        this.props = properties.llm();
        this.generateTimeout = Duration.ofSeconds(properties.pipeline().generationTimeoutSeconds());
    }

    public ServiceStatus checkHealth() {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> response = webClient.get()
                    .uri("/health")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(HEALTH_TIMEOUT);
            long elapsed = System.currentTimeMillis() - start;

            String status = response != null
                    ? response.getOrDefault("status", "ok").toString()
                    : "ok";

            return new ServiceStatus("llm-server", props.baseUrl(), true, status, elapsed, java.util.Map.of());
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("Serveur LLM indisponible: {}", e.getMessage());
            return ServiceStatus.unavailable("llm-server", props.baseUrl(), elapsed);
        }
    }

    /**
     * Génère une réponse via l'API OpenAI-compatible /v1/chat/completions.
     */
    @SuppressWarnings("unchecked")
    public String chat(String systemPrompt, String userMessage) {
        Map<String, Object> request = Map.of(
                "model", props.model(),
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)
                )
        );

        Map<String, Object> response = webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block(generateTimeout);

        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.getFirst().get("message");
        return (String) message.get("content");
    }

    /**
     * Liste les modèles disponibles sur le serveur LLM.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listModels() {
        Map<String, Object> response = webClient.get()
                .uri("/v1/models")
                .retrieve()
                .bodyToMono(Map.class)
                .block(HEALTH_TIMEOUT);

        Object data = response != null ? response.get("data") : null;
        if (data == null) return List.of();
        return (List<Map<String, Object>>) data;
    }

    public String getDefaultModel() {
        return props.model();
    }
}
