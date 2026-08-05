package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.QueryRequest;
import fr.spectra.model.AssistantPersona;
import fr.spectra.model.RagPromptFormat;
import fr.spectra.model.TrainingPair;
import fr.spectra.service.ActiveModelProfileService.ModelProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Personnalisation par modèle appliquée à la génération : la persona ({@code systemPrompt}) et
 * les paramètres ({@code parameters}) enregistrés avec le modèle actif doivent réellement
 * piloter le RAG, et non rester de simples métadonnées du registre.
 *
 * <p>Régression couverte : le prompt système RAG figeait la persona canonique dans le code et
 * la température venait d'un défaut du DTO, si bien qu'un modèle personnalisé était servi sous
 * une identité et des réglages qui n'étaient pas les siens.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagServiceModelProfileTest {

    @Mock private ChromaDbClient chromaDbClient;
    @Mock private EmbeddingService embeddingService;
    @Mock private LlmChatClient llmChatClient;
    @Mock private ActiveModelProfileService activeModelProfile;
    @Mock private SpectraProperties props;
    @Mock private SpectraProperties.PipelineProperties pipeline;

    private RagService ragService;

    @BeforeEach
    void setUp() {
        when(props.pipeline()).thenReturn(pipeline);
        when(pipeline.generationTimeoutSeconds()).thenReturn(30);
        when(props.chromadb()).thenReturn(null);
        when(props.longContextRag()).thenReturn(null);
        when(props.semanticDedup()).thenReturn(null);

        when(chromaDbClient.getOrCreateCollection(anyString())).thenReturn("col-1");
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));
        when(chromaDbClient.query(anyString(), anyList(), anyInt())).thenReturn(Map.of(
                "documents", List.of(List.of("Le péage de sortie est conforme.")),
                "metadatas", List.of(List.of(Map.of("sourceFile", "peage.json"))),
                "distances", List.of(List.of(0.12))));
        when(llmChatClient.chat(anyString(), anyString(), anyFloat(), anyFloat())).thenReturn("ok");
        when(llmChatClient.chatStream(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn(Flux.just("ok"));

        ragService = new RagService(
                chromaDbClient, embeddingService, llmChatClient,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                activeModelProfile, props, new ObjectMapper().findAndRegisterModules(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private void activeProfile(String persona, Float temperature, Float topP) {
        when(activeModelProfile.current())
                .thenReturn(new ModelProfile("modele-actif", persona, temperature, topP));
    }

    private static QueryRequest ragRequest(Float temperature, Float topP) {
        return new QueryRequest("Le péage est-il conforme ?", null, null, null,
                temperature, topP, null, true);
    }

    private static QueryRequest directRequest() {
        return new QueryRequest("Bonjour", null, null, null, null, null, null, false);
    }

    private String capturedSystemPrompt() {
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmChatClient).chat(systemPrompt.capture(), anyString(), anyFloat(), anyFloat());
        return systemPrompt.getValue();
    }

    // ── Persona du modèle actif ───────────────────────────────────────────────

    @Test
    void query_registeredPersona_prefixesTheRagSystemPrompt() {
        activeProfile("Tu es un juriste spécialisé en droit routier.", null, null);

        ragService.query(ragRequest(null, null));

        assertThat(capturedSystemPrompt())
                .startsWith("Tu es un juriste spécialisé en droit routier.")
                .doesNotContain(AssistantPersona.SYSTEM_PROMPT);
    }

    @Test
    void query_registeredPersona_keepsCitationInstructionsAndContext() {
        // La persona remplace la persona canonique, mais JAMAIS les consignes de citation ni
        // le bloc de contexte : sans elles, le RAG cesse de citer ses sources.
        activeProfile("Tu es un juriste.", null, null);

        ragService.query(ragRequest(null, null));

        assertThat(capturedSystemPrompt())
                .contains("=== CONTEXTE ===")
                .contains("[1] (Source: peage.json)")
                .contains("Le péage de sortie est conforme.")
                .contains("cite tes sources");
    }

    @Test
    @DisplayName("le prompt SERVI est mot pour mot celui sur lequel une paire ancrée entraîne")
    void servedPromptIsIdenticalToTheGroundedTrainingPrompt() {
        // Le test anti-dérive de F14, et le seul qui soit vraiment probant : il compare ce que
        // RagService envoie réellement au modèle avec ce qu'une paire d'entraînement ancrée
        // porte comme prompt système. Tant qu'ils sont identiques, le modèle est entraîné sur ce
        // qu'on lui sert ; toute divergence introduite d'un côté fait échouer ici.
        activeProfile(null, null, null);   // persona canonique, celle du fine-tuning

        ragService.query(ragRequest(null, null));

        TrainingPair grounded = TrainingPair.grounded(
                "Le péage est-il conforme ?", "Oui [1].",
                RagPromptFormat.contextBlock(List.of(
                        new RagPromptFormat.Passage("peage.json", "Le péage de sortie est conforme."))),
                "peage.json", "qa", "question_answer_grounded", 0.9, null);

        assertThat(capturedSystemPrompt()).isEqualTo(grounded.conversations().get(0).content());
    }

    @Test
    void query_withoutRegisteredPersona_keepsCanonicalPersona() {
        activeProfile(null, null, null);

        ragService.query(ragRequest(null, null));

        assertThat(capturedSystemPrompt()).startsWith(AssistantPersona.SYSTEM_PROMPT);
    }

    @Test
    void query_directMode_usesRegisteredPersona() {
        activeProfile("Tu es un juriste.", null, null);

        ragService.query(directRequest());

        assertThat(capturedSystemPrompt()).startsWith("Tu es un juriste.");
    }

    // ── Paramètres du modèle actif ────────────────────────────────────────────

    @Test
    void query_registeredParameters_driveGeneration() {
        activeProfile(null, 0.15f, 0.5f);

        ragService.query(ragRequest(null, null));

        verify(llmChatClient).chat(anyString(), anyString(), eqFloat(0.15f), eqFloat(0.5f));
    }

    @Test
    void query_explicitRequestParameters_overrideRegisteredOnes() {
        // Le curseur du Playground doit rester souverain : un modèle enregistré ne confisque
        // pas le réglage demandé pour une requête donnée.
        activeProfile(null, 0.15f, 0.5f);

        ragService.query(ragRequest(1.8f, 0.95f));

        verify(llmChatClient).chat(anyString(), anyString(), eqFloat(1.8f), eqFloat(0.95f));
    }

    @Test
    void query_withoutAnyParameters_fallsBackToSpectraDefaults() {
        activeProfile(null, null, null);

        ragService.query(ragRequest(null, null));

        verify(llmChatClient).chat(anyString(), anyString(), eqFloat(0.7f), eqFloat(0.9f));
    }

    @Test
    void query_directMode_appliesRegisteredParameters() {
        activeProfile(null, 0.15f, 0.5f);

        ragService.query(directRequest());

        verify(llmChatClient).chat(anyString(), anyString(), eqFloat(0.15f), eqFloat(0.5f));
    }

    // ── Chemin streaming ──────────────────────────────────────────────────────

    @Test
    void queryStream_appliesRegisteredPersonaAndParameters() {
        activeProfile("Tu es un juriste.", 0.15f, 0.5f);

        StepVerifier.create(ragService.queryStream(ragRequest(null, null)))
                .thenConsumeWhile(event -> true)
                .verifyComplete();

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmChatClient).chatStream(systemPrompt.capture(), anyString(),
                eqFloat(0.15f), eqFloat(0.5f));
        assertThat(systemPrompt.getValue())
                .startsWith("Tu es un juriste.")
                .contains("=== CONTEXTE ===");
    }

    @Test
    void queryStream_directMode_appliesRegisteredPersonaAndParameters() {
        activeProfile("Tu es un juriste.", 0.15f, 0.5f);

        StepVerifier.create(ragService.queryStream(directRequest()))
                .thenConsumeWhile(event -> true)
                .verifyComplete();

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmChatClient).chatStream(systemPrompt.capture(), anyString(),
                eqFloat(0.15f), eqFloat(0.5f));
        assertThat(systemPrompt.getValue()).startsWith("Tu es un juriste.");
    }

    /** {@code eq} typé float — évite l'ambiguïté d'auto-boxing des matchers Mockito. */
    private static float eqFloat(float value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
