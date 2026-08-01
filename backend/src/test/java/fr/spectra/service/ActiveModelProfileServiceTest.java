package fr.spectra.service;

import fr.spectra.dto.QueryRequest;
import fr.spectra.model.AssistantPersona;
import fr.spectra.service.ActiveModelProfileService.ModelProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Résolution du profil d'inférence du modèle de chat actif : ce que le registre déclare
 * (persona, paramètres) devient ce que le RAG applique, sans jamais confisquer une valeur
 * explicitement demandée par l'appelant.
 */
class ActiveModelProfileServiceTest {

    private ModelRegistryService registry;
    private ActiveModelProfileService profiles;

    @BeforeEach
    void setUp() {
        registry = mock(ModelRegistryService.class);
        profiles = new ActiveModelProfileService(registry);
    }

    private void activeModelIs(String alias, String systemPrompt, Map<String, Object> parameters) {
        when(registry.getActiveChatModel()).thenReturn(alias);
        when(registry.getModel(eq(alias), eq("chat"))).thenReturn(Optional.of(
                new ModelRegistryService.RegisteredModel(
                        alias, "chat", "llama-cpp", "/models/" + alias + ".gguf", "gguf",
                        systemPrompt, parameters, Instant.now(), "fine-tuning", null, null, null)));
    }

    // ── Résolution depuis le registre ─────────────────────────────────────────

    @Test
    void current_readsPersonaAndParametersFromRegistry() {
        activeModelIs("mon-ft", "Tu es un juriste.", Map.of("temperature", 0.15, "top_p", 0.5));

        ModelProfile profile = profiles.current();

        assertThat(profile.alias()).isEqualTo("mon-ft");
        assertThat(profile.persona()).isEqualTo("Tu es un juriste.");
        assertThat(profile.temperature()).isEqualTo(0.15f);
        assertThat(profile.topP()).isEqualTo(0.5f);
    }

    @Test
    void current_acceptsCamelCaseTopPAlias() {
        activeModelIs("m", null, Map.of("topP", 0.42));

        assertThat(profiles.current().topP()).isEqualTo(0.42f);
    }

    @Test
    void current_parsesStringParameters() {
        // Les paramètres du registre sont une carte libre : selon l'écrivain, une valeur
        // numérique peut arriver sous forme de chaîne.
        activeModelIs("m", null, Map.of("temperature", "0.25"));

        assertThat(profiles.current().temperature()).isEqualTo(0.25f);
    }

    @Test
    void current_ignoresNonNumericParameters() {
        // Le fine-tuning enregistre jobId/baseModel dans la même carte : ces clés ne doivent
        // pas perturber la résolution, et une valeur illisible retombe sur le défaut.
        activeModelIs("m", null, Map.of("jobId", "job-1", "baseModel", "qwen", "temperature", "n/a"));

        ModelProfile profile = profiles.current();

        assertThat(profile.temperature()).isNull();
        assertThat(profile.resolveTemperature(null)).isEqualTo(0.7f);
    }

    @Test
    void current_blankSystemPrompt_isTreatedAsAbsent() {
        activeModelIs("m", "   ", Map.of());

        assertThat(profiles.current().persona()).isNull();
    }

    @Test
    void current_unknownActiveModel_fallsBackToNeutralProfile() {
        when(registry.getActiveChatModel()).thenReturn("fantome");
        when(registry.getModel(eq("fantome"), eq("chat"))).thenReturn(Optional.empty());

        ModelProfile profile = profiles.current();

        assertThat(profile.persona()).isNull();
        assertThat(profile.personaOrDefault()).isEqualTo(AssistantPersona.SYSTEM_PROMPT);
        assertThat(profile.resolveTemperature(null)).isEqualTo(0.7f);
    }

    // ── Persona ───────────────────────────────────────────────────────────────

    @Test
    void personaOrDefault_withoutRegisteredPrompt_usesCanonicalPersona() {
        assertThat(ModelProfile.DEFAULT.personaOrDefault()).isEqualTo(AssistantPersona.SYSTEM_PROMPT);
    }

    @Test
    void personaOrDefault_withRegisteredPrompt_usesModelPersona() {
        activeModelIs("m", "Tu es un juriste.", Map.of());

        assertThat(profiles.current().personaOrDefault()).isEqualTo("Tu es un juriste.");
    }

    // ── Précédence des paramètres ─────────────────────────────────────────────

    @Test
    void resolve_explicitRequestValue_winsOverModelParameters() {
        activeModelIs("m", null, Map.of("temperature", 0.1, "top_p", 0.2));
        ModelProfile profile = profiles.current();

        assertThat(profile.resolveTemperature(1.8f)).isEqualTo(1.8f);
        assertThat(profile.resolveTopP(0.95f)).isEqualTo(0.95f);
    }

    @Test
    void resolve_absentRequestValue_usesModelParameters() {
        activeModelIs("m", null, Map.of("temperature", 0.1, "top_p", 0.2));
        ModelProfile profile = profiles.current();

        assertThat(profile.resolveTemperature(null)).isEqualTo(0.1f);
        assertThat(profile.resolveTopP(null)).isEqualTo(0.2f);
    }

    @Test
    void resolve_withoutRequestNorModelValue_usesSpectraDefaults() {
        assertThat(ModelProfile.DEFAULT.resolveTemperature(null)).isEqualTo(0.7f);
        assertThat(ModelProfile.DEFAULT.resolveTopP(null)).isEqualTo(0.9f);
    }

    // ── applyTo ───────────────────────────────────────────────────────────────

    @Test
    void applyTo_freezesResolvedParametersOnTheRequest() {
        activeModelIs("m", null, Map.of("temperature", 0.3, "top_p", 0.4));
        QueryRequest raw = new QueryRequest("Question ?", null, null, null, null, null, null, true);

        QueryRequest resolved = profiles.current().applyTo(raw);

        assertThat(resolved.temperature()).isEqualTo(0.3f);
        assertThat(resolved.topP()).isEqualTo(0.4f);
    }

    @Test
    void applyTo_preservesEveryOtherField() {
        activeModelIs("m", null, Map.of());
        QueryRequest raw = new QueryRequest("Question ?", 3, 12, "ma-collection",
                null, null, null, true);

        QueryRequest resolved = profiles.current().applyTo(raw);

        assertThat(resolved.question()).isEqualTo("Question ?");
        assertThat(resolved.maxContextChunks()).isEqualTo(3);
        assertThat(resolved.topCandidates()).isEqualTo(12);
        assertThat(resolved.collection()).isEqualTo("ma-collection");
        assertThat(resolved.useRag()).isTrue();
    }
}
