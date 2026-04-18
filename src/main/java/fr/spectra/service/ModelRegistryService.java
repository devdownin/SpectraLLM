package fr.spectra.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.spectra.config.SpectraProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registre local des modeles logiques utilises par Spectra (llama.cpp).
 */
@Service
public class ModelRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistryService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path registryPath;
    private final String defaultChatModel;
    private final String defaultEmbeddingModel;

    private RegistryState state;

    public ModelRegistryService(SpectraProperties properties) {
        SpectraProperties.LlmProperties llm = properties.llm();
        this.registryPath = Path.of(
                llm != null ? llm.effectiveRegistryPath() : "./data/models/registry.json"
        );

        String llmChatModel = llm != null && llm.chat() != null ? llm.chat().model() : null;
        String llmEmbeddingModel = llm != null && llm.embedding() != null ? llm.embedding().model() : null;

        this.defaultChatModel = llmChatModel != null ? llmChatModel
                : (llm != null && llm.model() != null ? llm.model() : "phi-4-mini");
        this.defaultEmbeddingModel = llmEmbeddingModel != null ? llmEmbeddingModel
                : (llm != null && llm.embeddingModel() != null ? llm.embeddingModel() : "nomic-embed-text");
    }

    @PostConstruct
    synchronized void init() {
        try {
            Files.createDirectories(registryPath.getParent());
            if (Files.exists(registryPath)) {
                state = MAPPER.readValue(registryPath.toFile(), RegistryState.class);
            }
        } catch (Exception e) {
            log.warn("Impossible de charger le registre des modèles {}: {}", registryPath, e.getMessage());
        }

        if (state == null) {
            state = new RegistryState(defaultChatModel, defaultEmbeddingModel, new ArrayList<>());
        }
        if (state.models() == null) {
            state = state.withModels(new ArrayList<>());
        }

        ensureModelExists(defaultChatModel, "chat", defaultChatModel, "alias", null, Map.of(), "bootstrap");
        ensureModelExists(defaultEmbeddingModel, "embedding", defaultEmbeddingModel, "alias", null, Map.of(), "bootstrap");

        if (state.activeChatModel() == null || state.activeChatModel().isBlank()) {
            state = state.withActiveChatModel(defaultChatModel);
        }
        if (state.activeEmbeddingModel() == null || state.activeEmbeddingModel().isBlank()) {
            state = state.withActiveEmbeddingModel(defaultEmbeddingModel);
        }

        persist();
    }

    public synchronized List<Map<String, Object>> listModels(String type) {
        return state.models().stream()
                .filter(model -> type == null || type.equals(model.type()))
                .map(this::toApiModel)
                .toList();
    }

    public synchronized boolean hasModel(String name, String type) {
        return findModel(name, type).isPresent();
    }

    public synchronized Optional<RegisteredModel> getModel(String name, String type) {
        return findModel(name, type);
    }

    public synchronized String getActiveChatModel() {
        return state.activeChatModel();
    }

    public synchronized String getActiveEmbeddingModel() {
        return state.activeEmbeddingModel();
    }

    public synchronized void setActiveChatModel(String name) {
        ensureModelExists(name, "chat", name, "alias", null, Map.of(), "manual-select");
        state = state.withActiveChatModel(name);
        persist();
    }

    public synchronized void setActiveEmbeddingModel(String name) {
        ensureModelExists(name, "embedding", name, "alias", null, Map.of(), "manual-select");
        state = state.withActiveEmbeddingModel(name);
        persist();
    }

    public synchronized void registerChatModel(String name, String source, String systemPrompt, Map<String, Object> parameters) {
        registerChatModel(name, source, systemPrompt, parameters, "fine-tuning");
    }

    public synchronized void registerChatModel(String name, String source, String systemPrompt,
                                               Map<String, Object> parameters, String provenance) {
        upsertModel(new RegisteredModel(
                name,
                "chat",
                "llama-cpp",
                source,
                inferSourceType(source),
                systemPrompt,
                parameters != null ? Map.copyOf(parameters) : Map.of(),
                Instant.now(),
                provenance
        ));
        persist();
    }

    public synchronized void registerEmbeddingModel(String name, String source) {
        registerEmbeddingModel(name, source, "manual");
    }

    public synchronized void registerEmbeddingModel(String name, String source, String provenance) {
        upsertModel(new RegisteredModel(
                name,
                "embedding",
                "llama-cpp",
                source,
                inferSourceType(source),
                null,
                Map.of(),
                Instant.now(),
                provenance
        ));
        persist();
    }

    public synchronized void registerModel(String name, String type, String source,
                                           String systemPrompt, Map<String, Object> parameters,
                                           String provenance, boolean activate) {
        String resolvedType = (type != null && !type.isBlank()) ? type : "chat";
        String resolvedProvenance = (provenance != null && !provenance.isBlank()) ? provenance : "api";

        if ("embedding".equals(resolvedType)) {
            registerEmbeddingModel(name, source, resolvedProvenance);
            if (activate) {
                setActiveEmbeddingModel(name);
            }
            return;
        }

        registerChatModel(name, source, systemPrompt, parameters, resolvedProvenance);
        if (activate) {
            setActiveChatModel(name);
        }
    }

    private Optional<RegisteredModel> findModel(String name, String type) {
        return state.models().stream()
                .filter(model -> model.name().equals(name))
                .filter(model -> type == null || type.equals(model.type()))
                .findFirst();
    }

    private void ensureModelExists(String name, String type, String source, String sourceType,
                                   String systemPrompt, Map<String, Object> parameters, String provenance) {
        if (findModel(name, type).isEmpty()) {
            upsertModel(new RegisteredModel(
                    name, type, "local", source, sourceType,
                    systemPrompt, parameters != null ? Map.copyOf(parameters) : Map.of(),
                    Instant.now(), provenance
            ));
        }
    }

    private void upsertModel(RegisteredModel model) {
        List<RegisteredModel> updated = new ArrayList<>();
        boolean replaced = false;

        for (RegisteredModel current : state.models()) {
            if (current.name().equals(model.name()) && current.type().equals(model.type())) {
                updated.add(model);
                replaced = true;
            } else {
                updated.add(current);
            }
        }

        if (!replaced) {
            updated.add(model);
        }

        state = state.withModels(updated);
    }

    private Map<String, Object> toApiModel(RegisteredModel model) {
        Map<String, Object> api = new LinkedHashMap<>();
        api.put("id", model.name());
        api.put("name", model.name());
        api.put("type", model.type());
        api.put("backend", model.backend());
        api.put("source", model.source());
        api.put("sourceType", model.sourceType());
        api.put("active", isActive(model));
        api.put("createdAt", model.createdAt());
        api.put("provenance", model.provenance());
        if (model.systemPrompt() != null) {
            api.put("systemPrompt", model.systemPrompt());
        }
        if (model.parameters() != null && !model.parameters().isEmpty()) {
            api.put("parameters", model.parameters());
        }
        return api;
    }

    private boolean isActive(RegisteredModel model) {
        return ("chat".equals(model.type()) && model.name().equals(state.activeChatModel()))
                || ("embedding".equals(model.type()) && model.name().equals(state.activeEmbeddingModel()));
    }

    private String inferSourceType(String source) {
        if (source == null || source.isBlank()) {
            return "alias";
        }
        String lower = source.toLowerCase();
        if (lower.endsWith(".gguf")) {
            return "gguf";
        }
        if (Files.exists(Path.of(source))) {
            return Files.isDirectory(Path.of(source)) ? "directory" : "file";
        }
        return "alias";
    }

    private void persist() {
        try {
            Files.createDirectories(registryPath.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(registryPath.toFile(), state);
        } catch (Exception e) {
            log.warn("Impossible de persister le registre des modèles {}: {}", registryPath, e.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegistryState(
            String activeChatModel,
            String activeEmbeddingModel,
            List<RegisteredModel> models) {

        public RegistryState withActiveChatModel(String name) {
            return new RegistryState(name, activeEmbeddingModel, models != null ? models : List.of());
        }

        public RegistryState withActiveEmbeddingModel(String name) {
            return new RegistryState(activeChatModel, name, models != null ? models : List.of());
        }

        public RegistryState withModels(List<RegisteredModel> updatedModels) {
            return new RegistryState(activeChatModel, activeEmbeddingModel, updatedModels);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegisteredModel(
            String name,
            String type,
            String backend,
            String source,
            String sourceType,
            String systemPrompt,
            Map<String, Object> parameters,
            Instant createdAt,
            String provenance) {
    }
}
