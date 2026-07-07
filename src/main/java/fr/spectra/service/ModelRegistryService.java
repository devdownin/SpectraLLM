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
    /** Source du modèle par défaut : chemin GGUF réel si le fichier est configuré, sinon alias. */
    private final String defaultChatSource;
    private final String defaultEmbeddingSource;

    private RegistryState state;

    public ModelRegistryService(SpectraProperties properties) {
        SpectraProperties.LlmProperties llm = properties.llm() != null
                ? properties.llm()
                : SpectraProperties.LlmProperties.defaults();

        this.registryPath = Path.of(llm.effectiveRegistryPath());
        this.defaultChatModel = llm.effectiveChatModel();
        this.defaultEmbeddingModel = llm.effectiveEmbeddingModel();
        this.defaultChatSource = resolveDefaultSource(llm.chat(), defaultChatModel);
        this.defaultEmbeddingSource = resolveDefaultSource(llm.embedding(), defaultEmbeddingModel);
    }

    /**
     * Source du modèle par défaut : le fichier GGUF configuré ({@code spectra.llm.*.file}),
     * résolu dans le répertoire des modèles (celui du registre, par convention le volume
     * partagé avec les conteneurs llama.cpp). À défaut, l'alias lui-même — comportement
     * historique, non servable par l'orchestrateur runtime.
     */
    private String resolveDefaultSource(SpectraProperties.EndpointProperties endpoint, String alias) {
        String file = endpoint != null ? endpoint.effectiveFile() : null;
        if (file == null) {
            return alias;
        }
        Path modelsDir = registryPath.getParent();
        Path source = modelsDir != null ? modelsDir.resolve(file) : Path.of(file);
        return source.toAbsolutePath().normalize().toString();
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

        bootstrapModel(defaultChatModel, "chat", defaultChatSource);
        bootstrapModel(defaultEmbeddingModel, "embedding", defaultEmbeddingSource);

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

    /**
     * Active un modèle de chat déjà enregistré.
     *
     * @throws IllegalArgumentException si l'alias est inconnu du registre. Un nom mal
     *         orthographié créait auparavant une entrée fantôme sans source (« alias »),
     *         impossible à servir ; on échoue désormais explicitement.
     */
    public synchronized void setActiveChatModel(String name) {
        requireRegistered(name, "chat");
        state = state.withActiveChatModel(name);
        persist();
    }

    /**
     * Active un modèle d'embedding déjà enregistré.
     *
     * @throws IllegalArgumentException si l'alias est inconnu du registre
     */
    public synchronized void setActiveEmbeddingModel(String name) {
        requireRegistered(name, "embedding");
        state = state.withActiveEmbeddingModel(name);
        persist();
    }

    public synchronized void registerChatModel(String name, String source, String systemPrompt, Map<String, Object> parameters) {
        registerChatModel(name, source, systemPrompt, parameters, "fine-tuning");
    }

    public synchronized void registerChatModel(String name, String source, String systemPrompt,
                                               Map<String, Object> parameters, String provenance) {
        registerChatModel(name, source, systemPrompt, parameters, provenance, ModelOrigin.UNKNOWN);
    }

    public synchronized void registerChatModel(String name, String source, String systemPrompt,
                                               Map<String, Object> parameters, String provenance,
                                               ModelOrigin origin) {
        upsertModel(new RegisteredModel(
                name,
                "chat",
                "llama-cpp",
                source,
                inferSourceType(source),
                systemPrompt,
                parameters != null ? Map.copyOf(parameters) : Map.of(),
                Instant.now(),
                provenance,
                origin.hfRepo(),
                origin.quantization(),
                origin.contextLength()
        ));
        persist();
    }

    public synchronized void registerEmbeddingModel(String name, String source) {
        registerEmbeddingModel(name, source, "manual");
    }

    public synchronized void registerEmbeddingModel(String name, String source, String provenance) {
        registerEmbeddingModel(name, source, provenance, ModelOrigin.UNKNOWN);
    }

    public synchronized void registerEmbeddingModel(String name, String source, String provenance,
                                                    ModelOrigin origin) {
        upsertModel(new RegisteredModel(
                name,
                "embedding",
                "llama-cpp",
                source,
                inferSourceType(source),
                null,
                Map.of(),
                Instant.now(),
                provenance,
                origin.hfRepo(),
                origin.quantization(),
                origin.contextLength()
        ));
        persist();
    }

    public synchronized void registerModel(String name, String type, String source,
                                           String systemPrompt, Map<String, Object> parameters,
                                           String provenance, boolean activate) {
        registerModel(name, type, source, systemPrompt, parameters, provenance, activate, ModelOrigin.UNKNOWN);
    }

    public synchronized void registerModel(String name, String type, String source,
                                           String systemPrompt, Map<String, Object> parameters,
                                           String provenance, boolean activate, ModelOrigin origin) {
        String resolvedType = (type != null && !type.isBlank()) ? type : "chat";
        String resolvedProvenance = (provenance != null && !provenance.isBlank()) ? provenance : "api";

        if ("embedding".equals(resolvedType)) {
            registerEmbeddingModel(name, source, resolvedProvenance, origin);
            if (activate) {
                setActiveEmbeddingModel(name);
            }
            return;
        }

        registerChatModel(name, source, systemPrompt, parameters, resolvedProvenance, origin);
        if (activate) {
            setActiveChatModel(name);
        }
    }

    /**
     * Retire un modèle du registre, avec suppression optionnelle de son fichier GGUF.
     *
     * <p>Garde-fous :</p>
     * <ul>
     *   <li>le modèle <b>actif</b> (chat ou embedding) n'est pas supprimable — il faut
     *       d'abord en activer un autre ;</li>
     *   <li>le fichier n'est supprimé que s'il réside dans le répertoire des modèles
     *       (celui du registre) et qu'<b>aucun autre</b> modèle enregistré ne le référence.</li>
     * </ul>
     *
     * @return compte-rendu : {@code name}, {@code type}, {@code fileDeleted}, {@code fileSkippedReason}
     * @throws IllegalArgumentException modèle inconnu
     * @throws IllegalStateException    modèle actif
     */
    public synchronized Map<String, Object> removeModel(String name, String type, boolean deleteFile) {
        String resolvedType = (type != null && !type.isBlank()) ? type : "chat";
        RegisteredModel model = findModel(name, resolvedType).orElseThrow(() -> new IllegalArgumentException(
                "Modèle inconnu du registre : '" + name + "' (type=" + resolvedType + ")"));

        boolean active = ("chat".equals(resolvedType) && name.equals(state.activeChatModel()))
                || ("embedding".equals(resolvedType) && name.equals(state.activeEmbeddingModel()));
        if (active) {
            throw new IllegalStateException("Le modèle actif '" + name + "' ne peut pas être supprimé : "
                    + "activez d'abord un autre modèle de " + resolvedType + ".");
        }

        state = state.withModels(state.models().stream()
                .filter(current -> !(current.name().equals(name) && current.type().equals(resolvedType)))
                .toList());
        persist();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("type", resolvedType);
        result.put("status", "deleted");
        if (deleteFile) {
            deleteModelFile(model, result);
        } else {
            result.put("fileDeleted", false);
        }
        log.info("Modèle '{}' ({}) retiré du registre{}", name, resolvedType,
                Boolean.TRUE.equals(result.get("fileDeleted")) ? " — fichier supprimé : " + model.source() : "");
        return result;
    }

    /** Supprime le GGUF du modèle si — et seulement si — c'est sûr (cf. {@link #removeModel}). */
    private void deleteModelFile(RegisteredModel model, Map<String, Object> result) {
        result.put("fileDeleted", false);
        String source = model.source();
        if (source == null || !source.toLowerCase().endsWith(".gguf")) {
            result.put("fileSkippedReason", "La source n'est pas un fichier GGUF : " + source);
            return;
        }
        Path file = Path.of(source).toAbsolutePath().normalize();
        Path modelsDir = registryPath.getParent() != null
                ? registryPath.getParent().toAbsolutePath().normalize() : null;
        if (modelsDir == null || !file.startsWith(modelsDir)) {
            result.put("fileSkippedReason", "Fichier hors du répertoire des modèles — non supprimé : " + file);
            return;
        }
        boolean shared = state.models().stream().anyMatch(other -> source.equals(other.source()));
        if (shared) {
            result.put("fileSkippedReason", "Fichier encore référencé par un autre modèle enregistré — non supprimé");
            return;
        }
        try {
            boolean deleted = Files.deleteIfExists(file);
            result.put("fileDeleted", deleted);
            if (!deleted) {
                result.put("fileSkippedReason", "Fichier déjà absent : " + file);
            }
        } catch (Exception e) {
            result.put("fileSkippedReason", "Suppression impossible : " + e.getMessage());
        }
    }

    private void requireRegistered(String name, String type) {
        if (name == null || name.isBlank() || findModel(name, type).isEmpty()) {
            List<String> known = state.models().stream()
                    .filter(model -> type.equals(model.type()))
                    .map(RegisteredModel::name)
                    .toList();
            throw new IllegalArgumentException(
                    "Modèle " + ("embedding".equals(type) ? "d'embedding" : "de chat")
                    + " inconnu du registre : '" + name + "'. Modèles enregistrés : " + known
                    + ". Enregistrez-le d'abord (fine-tuning, llmfit ou POST /api/fine-tuning/models/register).");
        }
    }

    private Optional<RegisteredModel> findModel(String name, String type) {
        return state.models().stream()
                .filter(model -> model.name().equals(name))
                .filter(model -> type == null || type.equals(model.type()))
                .findFirst();
    }

    /**
     * Crée l'entrée du modèle par défaut si elle manque, et <b>répare</b> une entrée
     * bootstrap historique de type « alias » (sans chemin) quand le fichier GGUF réel
     * est désormais connu par la configuration — l'entrée devient alors servable.
     * Les entrées enregistrées avec une vraie source (fine-tuning, llmfit, api) ne sont
     * jamais écrasées.
     */
    private void bootstrapModel(String name, String type, String source) {
        Optional<RegisteredModel> existing = findModel(name, type);
        if (existing.isEmpty()) {
            upsertModel(new RegisteredModel(
                    name, type, "local", source, inferSourceType(source),
                    null, Map.of(), Instant.now(), "bootstrap", null, null, null
            ));
            return;
        }

        RegisteredModel current = existing.get();
        boolean realSourceKnown = !source.equals(name);
        if (realSourceKnown && "alias".equals(current.sourceType()) && !source.equals(current.source())) {
            log.info("Registre : source du modèle par défaut '{}' ({}) mise à jour → {}", name, type, source);
            upsertModel(new RegisteredModel(
                    current.name(), current.type(), current.backend(), source, inferSourceType(source),
                    current.systemPrompt(), current.parameters(), current.createdAt(),
                    current.provenance(), current.hfRepo(), current.quantization(), current.contextLength()
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
        if (model.hfRepo() != null) {
            api.put("hfRepo", model.hfRepo());
        }
        if (model.quantization() != null) {
            api.put("quantization", model.quantization());
        }
        if (model.contextLength() != null) {
            api.put("contextLength", model.contextLength());
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
        materializeActiveChatPointer();
    }

    /**
     * Matérialise le modèle de chat actif dans un fichier pointeur trivialement parsable
     * ({@code active-chat-model}, à côté du registre donc sur le volume partagé des modèles) :
     * <pre>
     *   ligne 1 : alias du modèle actif
     *   ligne 2 : nom du fichier GGUF dans le volume (absente si la source n'est pas un GGUF)
     * </pre>
     * C'est le contrat lu par l'entrypoint superviseur de llm-chat
     * ({@code scripts/llm-chat-entrypoint.sh}) pour converger automatiquement vers le
     * modèle actif après un {@code POST /api/config/model} — registry.json reste la source
     * de vérité, ce fichier n'en est qu'une vue dérivée (réécrite à chaque persistance).
     */
    private void materializeActiveChatPointer() {
        try {
            String active = state.activeChatModel();
            Path modelsDir = registryPath.getParent();
            if (active == null || active.isBlank() || modelsDir == null) {
                return;
            }

            StringBuilder content = new StringBuilder(active).append('\n');
            findModel(active, "chat")
                    .map(RegisteredModel::source)
                    .filter(source -> source != null && source.toLowerCase().endsWith(".gguf"))
                    .ifPresent(source -> content.append(Path.of(source).getFileName()).append('\n'));

            // Écriture atomique : llm-chat peut lire le pointeur à tout instant.
            Path pointer = modelsDir.resolve("active-chat-model");
            Path tmp = modelsDir.resolve("active-chat-model.tmp");
            Files.writeString(tmp, content.toString());
            try {
                Files.move(tmp, pointer, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Certains systèmes de fichiers (montages réseau) ne supportent pas le move
                // atomique : un remplacement simple reste préférable à aucun pointeur.
                Files.move(tmp, pointer, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("Impossible d'écrire le pointeur du modèle actif : {}", e.getMessage());
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
            String provenance,
            // ── Traçabilité d'origine (nullable — absents des registres antérieurs) ──
            /** Repo HuggingFace d'origine (modèle téléchargé, ou base d'un fine-tuning). */
            String hfRepo,
            /** Quantisation du GGUF (Q4_K_M, q8_0, …) quand elle est connue. */
            String quantization,
            /** Fenêtre de contexte d'entraînement (n_ctx_train) quand elle est connue. */
            Integer contextLength) {
    }

    /**
     * Origine d'un modèle au moment de son enregistrement (tous les champs optionnels).
     * Rend le registre auto-descriptif : d'où vient le fichier, dans quelle quantisation,
     * avec quelle fenêtre de contexte — sans devoir recouper .env, scripts et docs.
     */
    public record ModelOrigin(String hfRepo, String quantization, Integer contextLength) {
        public static final ModelOrigin UNKNOWN = new ModelOrigin(null, null, null);
    }
}
