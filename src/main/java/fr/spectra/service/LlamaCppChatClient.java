package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.ServiceStatus;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client de chat pour llama-server.
 *
 * Source primaire: README officiel de llama.cpp, qui documente l'endpoint
 * OpenAI-compatible `/v1/chat/completions`.
 */
@Service
@ConditionalOnProperty(prefix = "spectra.llm", name = "provider", havingValue = "llama-cpp")
public class LlamaCppChatClient implements LlmChatClient {

    private static final Logger log = LoggerFactory.getLogger(LlamaCppChatClient.class);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final String baseUrl;
    private final Duration generateTimeout;
    private final ModelRegistryService modelRegistry;
    private final LlamaCppRuntimeOrchestrator runtimeOrchestrator;
    private final ObjectMapper objectMapper;
    private final AtomicReference<String> activeModel;

    public LlamaCppChatClient(@Qualifier("llamaCppChatWebClient") WebClient webClient,
                              ModelRegistryService modelRegistry,
                              LlamaCppRuntimeOrchestrator runtimeOrchestrator,
                              ObjectMapper objectMapper,
                              SpectraProperties properties) {
        this.webClient = webClient;
        this.modelRegistry = modelRegistry;
        this.runtimeOrchestrator = runtimeOrchestrator;
        this.objectMapper = objectMapper;

        SpectraProperties.EndpointProperties chat = properties.llm() != null ? properties.llm().chat() : null;
        SpectraProperties.RuntimeProperties runtime = properties.llm() != null ? properties.llm().runtime() : null;
        this.baseUrl = runtime != null && runtime.enabled()
                ? "http://127.0.0.1:" + runtime.effectivePort()
                : chat != null && chat.baseUrl() != null
                    ? chat.baseUrl()
                    : "http://llama-cpp-chat:8080";
        String model = chat != null && chat.model() != null
                ? chat.model()
                : (properties.llm() != null && properties.llm().model() != null ? properties.llm().model() : "phi-4-mini");
        int timeoutSeconds = chat != null
                ? chat.effectiveTimeoutSeconds(properties.pipeline().generationTimeoutSeconds())
                : properties.pipeline().generationTimeoutSeconds();

        this.activeModel = new AtomicReference<>(modelRegistry.getActiveChatModel() != null
                ? modelRegistry.getActiveChatModel()
                : model);
        this.generateTimeout = Duration.ofSeconds(timeoutSeconds);

        log.info("Provider llama.cpp actif pour le chat — baseUrl={}, model={}, timeout={}s",
                baseUrl, activeModel.get(), timeoutSeconds);
    }

    @Override
    public void setActiveModel(String model) {
        String previous = activeModel.getAndSet(model);
        modelRegistry.setActiveChatModel(model);
        runtimeOrchestrator.ensureChatModelServed(model);
        log.info("Modèle actif llama.cpp changé : {} → {}", previous, model);
    }

    @Override
    public String getActiveModel() {
        return activeModel.get();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ServiceStatus checkHealth() {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> response = webClient.get()
                    .uri("/v1/models")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(HEALTH_TIMEOUT);
            long elapsed = System.currentTimeMillis() - start;

            List<Map<String, Object>> models = response != null && response.get("data") instanceof List<?> data
                    ? (List<Map<String, Object>>) data
                    : List.of();
            List<String> modelNames = models.stream()
                    .map(this::modelName)
                    .toList();
            String activeSource = modelRegistry.getModel(activeModel.get(), "chat")
                    .map(ModelRegistryService.RegisteredModel::source)
                    .orElse(activeModel.get());
            boolean activeModelLoaded = modelNames.stream()
                    .anyMatch(name -> name != null && (name.equals(activeModel.get()) || name.equals(activeSource)));

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("activeModel", activeModel.get());
            details.put("activeModelLoaded", activeModelLoaded);
            details.put("availableModels", modelNames);
            details.put("registeredModels", modelRegistry.listModels("chat"));
            details.put("runtime", runtimeOrchestrator.runtimeStatus());

            return new ServiceStatus("llama-cpp", baseUrl, true, "ok", elapsed, details);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("llama.cpp indisponible: {}", e.getMessage());
            return ServiceStatus.unavailable("llama-cpp", baseUrl, elapsed);
        }
    }

    @Override
    @CircuitBreaker(name = "llm-chat", fallbackMethod = "chatFallback")
    @SuppressWarnings("unchecked")
    public String chat(String systemPrompt, String userMessage) {
        Map<String, Object> request = Map.of(
                "model", activeModel.get(),
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)
                )
        );

        Map<String, Object> response = webClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block(generateTimeout);

        if (response == null) {
            throw new IllegalStateException("Réponse null de llama.cpp /v1/chat/completions");
        }

        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("Champ 'choices' absent de la réponse llama.cpp: " + response.keySet());
        }

        Map<String, Object> choice = choices.getFirst();
        Map<String, Object> message = (Map<String, Object>) choice.get("message");
        if (message == null) {
            throw new IllegalStateException("Champ 'message' absent de la réponse llama.cpp: " + choice.keySet());
        }

        String content = (String) message.get("content");
        if (content == null) {
            throw new IllegalStateException("Champ 'content' absent du message llama.cpp: " + message.keySet());
        }
        return content;
    }

    /**
     * Fallback du circuit breaker : levé quand le circuit est ouvert ou que le quota
     * d'échecs est dépassé. Retourne une {@link LlmUnavailableException} mappée en 503.
     */
    String chatFallback(String systemPrompt, String userMessage, Throwable cause) {
        log.warn("[circuit-breaker] llm-chat ouvert — cause: {}", cause.getMessage());
        throw new LlmUnavailableException(
                "Le modèle de langage est temporairement indisponible. "
                + "Réessayez dans quelques instants.", cause);
    }

    /**
     * Streaming token par token via {@code "stream": true} sur /v1/chat/completions.
     *
     * <p>llama-server émet des lignes SSE de la forme :
     * {@code data: {"choices":[{"delta":{"content":"token"},...}]}}
     * suivies de {@code data: [DONE]}.
     *
     * <p>Chaque token non vide est émis comme élément du {@code Flux}.
     * Les erreurs réseau sont propagées dans le Flux pour gestion amont.
     */
    @Override
    @SuppressWarnings("unchecked")
    public Flux<String> chatStream(String systemPrompt, String userMessage) {
        Map<String, Object> request = Map.of(
                "model", activeModel.get(),
                "stream", true,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)
                )
        );

        return webClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> line.startsWith("data: ") && !line.equals("data: [DONE]"))
                .mapNotNull(this::extractTokenFromSseLine)
                .filter(token -> !token.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private String extractTokenFromSseLine(String line) {
        try {
            String json = line.substring("data: ".length()).trim();
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
            if (choices == null || choices.isEmpty()) return null;
            Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
            if (delta == null) return null;
            String content = (String) delta.get("content");
            return content != null ? content : "";
        } catch (Exception e) {
            log.debug("Ligne SSE ignorée (parse): {}", line);
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listModels() {
        List<Map<String, Object>> registered = modelRegistry.listModels("chat");
        if (!registered.isEmpty()) {
            return registered;
        }

        return List.of(Map.of(
                "id", activeModel.get(),
                "name", activeModel.get(),
                "owned_by", "llama.cpp"
        ));
    }

    @Override
    public void createModel(String name, String from, String system, Map<String, Object> params) {
        modelRegistry.registerChatModel(name, from, system, params);
        log.info("Modèle '{}' enregistré dans le registre local llama.cpp (source={})", name, from);

        if (name.equals(activeModel.get())) {
            runtimeOrchestrator.ensureChatModelServed(name);
        }
    }

    @Override
    public void pullModel(String name) {
        throw new UnsupportedOperationException(
                "pullModel n'est pas supporté avec le provider llama-cpp. "
                + "Les modèles doivent être gérés localement sous forme de GGUF.");
    }

    @Override
    public boolean hasModel(String name) {
        return modelRegistry.hasModel(name, "chat") || listModels().stream()
                .map(this::modelName)
                .filter(model -> model != null)
                .anyMatch(model -> model.equals(name));
    }

    @Override
    public String getDefaultModel() {
        return activeModel.get();
    }

    private String modelName(Map<String, Object> model) {
        Object id = model.get("id");
        if (id != null) return id.toString();
        Object name = model.get("name");
        return name != null ? name.toString() : null;
    }
}
