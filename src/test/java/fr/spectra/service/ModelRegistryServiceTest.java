package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de ModelRegistryService.
 * Chaque test utilise un répertoire temporaire isolé pour le fichier registry.json.
 */
class ModelRegistryServiceTest {

    @TempDir
    Path tempDir;

    private ModelRegistryService registry;

    @BeforeEach
    void setUp() {
        registry = buildRegistry(tempDir.resolve("registry.json").toString());
        registry.init();
    }

    private ModelRegistryService buildRegistry(String registryPath) {
        SpectraProperties.LlmProperties llm = new SpectraProperties.LlmProperties(
                null, "default-chat", "default-embed", null, registryPath, null, null, null);
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.llm()).thenReturn(llm);
        return new ModelRegistryService(props);
    }

    // ── initialisation ────────────────────────────────────────────────────────

    @Test
    void init_bootstrapsDefaultChatModel() {
        assertThat(registry.hasModel("default-chat", "chat")).isTrue();
    }

    @Test
    void init_bootstrapsDefaultEmbeddingModel() {
        assertThat(registry.hasModel("default-embed", "embedding")).isTrue();
    }

    @Test
    void init_activeChatModel_isDefault() {
        assertThat(registry.getActiveChatModel()).isEqualTo("default-chat");
    }

    @Test
    void init_activeEmbeddingModel_isDefault() {
        assertThat(registry.getActiveEmbeddingModel()).isEqualTo("default-embed");
    }

    // ── listModels ────────────────────────────────────────────────────────────

    @Test
    void listModels_noFilter_returnsAllTypes() {
        registry.registerChatModel("chat-a", "/models/a.gguf", null, Map.of(), "llmfit");
        registry.registerEmbeddingModel("embed-b", "/models/b.gguf", "llmfit");

        List<Map<String, Object>> all = registry.listModels(null);
        assertThat(all).extracting(m -> m.get("name"))
                .contains("chat-a", "embed-b");
    }

    @Test
    void listModels_chatFilter_excludesEmbedding() {
        registry.registerEmbeddingModel("embed-only", "/models/embed.gguf", "llmfit");

        List<Map<String, Object>> chats = registry.listModels("chat");
        assertThat(chats).noneMatch(m -> "embed-only".equals(m.get("name")));
        assertThat(chats).allMatch(m -> "chat".equals(m.get("type")));
    }

    @Test
    void listModels_embeddingFilter_excludesChat() {
        registry.registerChatModel("chat-only", "/models/chat.gguf", null, Map.of(), "llmfit");

        List<Map<String, Object>> embeddings = registry.listModels("embedding");
        assertThat(embeddings).noneMatch(m -> "chat-only".equals(m.get("name")));
        assertThat(embeddings).allMatch(m -> "embedding".equals(m.get("type")));
    }

    @Test
    void listModels_contains_activeFlag() {
        registry.registerChatModel("active-model", "/models/active.gguf", null, Map.of(), "llmfit");
        registry.setActiveChatModel("active-model");

        List<Map<String, Object>> chats = registry.listModels("chat");
        assertThat(chats).anyMatch(m -> "active-model".equals(m.get("name")) && Boolean.TRUE.equals(m.get("active")));
    }

    // ── registerChatModel avec provenance llmfit ──────────────────────────────

    @Test
    void registerChatModel_llmfitProvenance_addsModel() {
        registry.registerChatModel("llama3-q4", "/models/llama3.gguf", null, Map.of(), "llmfit");

        assertThat(registry.hasModel("llama3-q4", "chat")).isTrue();
    }

    @Test
    void registerChatModel_llmfitProvenance_provenanceFieldSet() {
        registry.registerChatModel("phi-4-mini", "/models/phi4.gguf", null, Map.of(), "llmfit");

        Map<String, Object> found = registry.listModels("chat").stream()
                .filter(m -> "phi-4-mini".equals(m.get("name"))).findFirst().orElseThrow();
        assertThat(found.get("provenance")).isEqualTo("llmfit");
    }

    @Test
    void registerChatModel_ggufPath_sourceTypeIsGguf() {
        registry.registerChatModel("mistral-7b", "/models/mistral.gguf", null, Map.of(), "llmfit");

        Map<String, Object> found = registry.listModels("chat").stream()
                .filter(m -> "mistral-7b".equals(m.get("name"))).findFirst().orElseThrow();
        assertThat(found.get("sourceType")).isEqualTo("gguf");
    }

    @Test
    void registerChatModel_withSystemPrompt_systemPromptPersisted() {
        registry.registerChatModel("assistant", "/models/asst.gguf",
                "Tu es un assistant IA spécialisé.", Map.of(), "llmfit");

        Map<String, Object> found = registry.listModels("chat").stream()
                .filter(m -> "assistant".equals(m.get("name"))).findFirst().orElseThrow();
        assertThat(found.get("systemPrompt")).isEqualTo("Tu es un assistant IA spécialisé.");
    }

    @Test
    void registerChatModel_duplicate_replacesNotDuplicates() {
        registry.registerChatModel("model-x", "/v1.gguf", null, Map.of(), "llmfit");
        registry.registerChatModel("model-x", "/v2.gguf", null, Map.of(), "llmfit");

        List<Map<String, Object>> chats = registry.listModels("chat");
        long count = chats.stream().filter(m -> "model-x".equals(m.get("name"))).count();
        assertThat(count).isEqualTo(1);

        Map<String, Object> model = chats.stream()
                .filter(m -> "model-x".equals(m.get("name"))).findFirst().orElseThrow();
        assertThat(model.get("source")).isEqualTo("/v2.gguf");
    }

    // ── setActiveChatModel ────────────────────────────────────────────────────

    @Test
    void setActiveChatModel_updatesActiveModel() {
        registry.registerChatModel("new-active", "/models/new.gguf", null, Map.of(), "llmfit");
        registry.setActiveChatModel("new-active");

        assertThat(registry.getActiveChatModel()).isEqualTo("new-active");
    }

    @Test
    void setActiveChatModel_marksModelActiveInListing() {
        registry.registerChatModel("target", "/models/target.gguf", null, Map.of(), "llmfit");
        registry.setActiveChatModel("target");

        Map<String, Object> found = registry.listModels("chat").stream()
                .filter(m -> "target".equals(m.get("name"))).findFirst().orElseThrow();
        assertThat(found.get("active")).isEqualTo(true);
    }

    @Test
    void setActiveChatModel_switchActive_deactivatesPrevious() {
        registry.registerChatModel("model-a", "/a.gguf", null, Map.of(), "llmfit");
        registry.registerChatModel("model-b", "/b.gguf", null, Map.of(), "llmfit");
        registry.setActiveChatModel("model-a");
        registry.setActiveChatModel("model-b");

        Map<String, Object> modelA = registry.listModels("chat").stream()
                .filter(m -> "model-a".equals(m.get("name"))).findFirst().orElseThrow();
        assertThat(modelA.get("active")).isEqualTo(false);
    }

    // ── hasModel ──────────────────────────────────────────────────────────────

    @Test
    void hasModel_existingModel_returnsTrue() {
        registry.registerChatModel("known", "/models/known.gguf", null, Map.of(), "llmfit");
        assertThat(registry.hasModel("known", "chat")).isTrue();
    }

    @Test
    void hasModel_unknownModel_returnsFalse() {
        assertThat(registry.hasModel("nonexistent", "chat")).isFalse();
    }

    @Test
    void hasModel_wrongType_returnsFalse() {
        registry.registerChatModel("chat-model", "/models/chat.gguf", null, Map.of(), "llmfit");
        assertThat(registry.hasModel("chat-model", "embedding")).isFalse();
    }

    @Test
    void hasModel_nullType_matchesAnyType() {
        registry.registerChatModel("any-model", "/models/any.gguf", null, Map.of(), "llmfit");
        assertThat(registry.hasModel("any-model", null)).isTrue();
    }

    // ── registerEmbeddingModel ────────────────────────────────────────────────

    @Test
    void registerEmbeddingModel_llmfitProvenance_addsEmbedding() {
        registry.registerEmbeddingModel("nomic-embed", "/models/nomic.gguf", "llmfit");

        assertThat(registry.hasModel("nomic-embed", "embedding")).isTrue();
        Map<String, Object> found = registry.listModels("embedding").stream()
                .filter(m -> "nomic-embed".equals(m.get("name"))).findFirst().orElseThrow();
        assertThat(found.get("provenance")).isEqualTo("llmfit");
        assertThat(found.get("type")).isEqualTo("embedding");
    }

    // ── registerModel (activation) ────────────────────────────────────────────

    @Test
    void registerModel_chatWithActivate_setsChatActive() {
        registry.registerModel("fast-chat", "chat", "/models/fast.gguf",
                "Réponds en français.", Map.of(), "llmfit", true);

        assertThat(registry.getActiveChatModel()).isEqualTo("fast-chat");
    }

    @Test
    void registerModel_embeddingWithActivate_setsEmbeddingActive() {
        registry.registerModel("embed-v2", "embedding", "/models/embed-v2.gguf",
                null, Map.of(), "llmfit", true);

        assertThat(registry.getActiveEmbeddingModel()).isEqualTo("embed-v2");
    }

    @Test
    void registerModel_chatWithoutActivate_doesNotChangeActive() {
        String originalActive = registry.getActiveChatModel();
        registry.registerModel("inactive-chat", "chat", "/models/inactive.gguf",
                null, Map.of(), "llmfit", false);

        assertThat(registry.getActiveChatModel()).isEqualTo(originalActive);
    }

    // ── persistance du registre ────────────────────────────────────────────────

    @Test
    void persist_reloadedRegistry_preservesLlmfitModels() {
        registry.registerChatModel("persisted", "/models/p.gguf", null, Map.of(), "llmfit");
        registry.setActiveChatModel("persisted");

        ModelRegistryService reloaded = buildRegistry(tempDir.resolve("registry.json").toString());
        reloaded.init();

        assertThat(reloaded.hasModel("persisted", "chat")).isTrue();
        assertThat(reloaded.getActiveChatModel()).isEqualTo("persisted");
    }

    @Test
    void persist_reloadedRegistry_provenanceSurvivesRestart() {
        registry.registerChatModel("llmfit-model", "/models/llmfit.gguf", null, Map.of(), "llmfit");

        ModelRegistryService reloaded = buildRegistry(tempDir.resolve("registry.json").toString());
        reloaded.init();

        Map<String, Object> model = reloaded.listModels("chat").stream()
                .filter(m -> "llmfit-model".equals(m.get("name"))).findFirst().orElseThrow();
        assertThat(model.get("provenance")).isEqualTo("llmfit");
    }

    @Test
    void persist_reloadedRegistry_activeEmbeddingModelPreserved() {
        registry.registerEmbeddingModel("embed-persist", "/models/embed.gguf", "llmfit");
        registry.setActiveEmbeddingModel("embed-persist");

        ModelRegistryService reloaded = buildRegistry(tempDir.resolve("registry.json").toString());
        reloaded.init();

        assertThat(reloaded.getActiveEmbeddingModel()).isEqualTo("embed-persist");
    }
}
