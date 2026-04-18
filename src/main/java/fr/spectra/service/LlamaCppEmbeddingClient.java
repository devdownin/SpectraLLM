package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.ServiceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Client d'embeddings pour llama-server.
 *
 * Source primaire: le projet llama.cpp expose un serveur OpenAI-compatible,
 * et des issues officielles documentent l'usage de `/v1/embeddings` avec `--embeddings`.
 */
@Service
@ConditionalOnProperty(prefix = "spectra.llm", name = "provider", havingValue = "llama-cpp")
public class LlamaCppEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(LlamaCppEmbeddingClient.class);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final String embeddingModel;
    private final String baseUrl;
    private final Duration timeout;

    public LlamaCppEmbeddingClient(@Qualifier("llamaCppEmbeddingWebClient") WebClient webClient,
                                   SpectraProperties properties) {
        this.webClient = webClient;

        SpectraProperties.EndpointProperties embedding = properties.llm() != null ? properties.llm().embedding() : null;
        this.embeddingModel = embedding != null && embedding.model() != null
                ? embedding.model()
                : (properties.llm() != null && properties.llm().embeddingModel() != null ? properties.llm().embeddingModel() : "nomic-embed-text");
        this.baseUrl = embedding != null && embedding.baseUrl() != null
                ? embedding.baseUrl()
                : "http://llama-cpp-embed:8080";
        int timeoutSeconds = embedding != null
                ? embedding.effectiveTimeoutSeconds(30)
                : 30;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ServiceStatus checkHealth() {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> response = webClient.get()
                    .uri("/health")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(HEALTH_TIMEOUT);
            long elapsed = System.currentTimeMillis() - start;

            String serverStatus = response != null ? String.valueOf(response.get("status")) : "unknown";
            // llama-server retourne "ok" quand le modèle est chargé, "loading model" pendant le démarrage
            boolean ok = "ok".equals(serverStatus) || "loading model".equals(serverStatus);

            return new ServiceStatus(
                    "llama-cpp-embed",
                    baseUrl,
                    ok,
                    serverStatus,
                    elapsed,
                    Map.of("activeModel", embeddingModel, "serverStatus", serverStatus)
            );
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("llama.cpp embed indisponible: {}", e.getMessage());
            return ServiceStatus.unavailable("llama-cpp-embed", baseUrl, elapsed);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Float> embed(String text) {
        Map<String, Object> request = Map.of(
                "model", embeddingModel,
                "input", text
        );

        Map<String, Object> response = webClient.post()
                .uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block(timeout);

        if (response == null) {
            throw new IllegalStateException("Réponse null de llama.cpp /v1/embeddings");
        }

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        if (data == null || data.isEmpty()) {
            throw new IllegalStateException("Champ 'data' absent de la réponse llama.cpp embeddings: " + response.keySet());
        }

        List<Double> embedding = (List<Double>) data.getFirst().get("embedding");
        if (embedding == null) {
            throw new IllegalStateException("Champ 'embedding' absent de la réponse llama.cpp embeddings");
        }

        return embedding.stream().map(Double::floatValue).toList();
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        return texts.stream()
                .map(this::embed)
                .toList();
    }
}
