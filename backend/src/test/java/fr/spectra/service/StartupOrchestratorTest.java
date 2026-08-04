package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Deux défauts distincts vivaient sur le chemin de rattrapage des modèles, et tous deux
 * aboutissaient à un téléchargement de plusieurs gigaoctets non demandé.
 *
 * <ol>
 *   <li>Le repli de {@code resolveChatModelFile()} rendait l'ALIAS du modèle
 *       ({@code qwen2.5-7b-instruct}) là où un nom de FICHIER était attendu
 *       ({@code Qwen2.5-7B-Instruct-Q4_K_M.gguf}). Résolu en chemin, l'alias ne désigne
 *       jamais rien : le GGUF était déclaré manquant à chaque démarrage, présent ou non.</li>
 *   <li>Quel que soit le fichier jugé absent, c'est le dépôt HuggingFace par défaut, en dur,
 *       qui était installé — avec {@code autoActivate=true}. Configurer un autre modèle
 *       revenait donc à télécharger un modèle tiers, à l'activer par-dessus son choix, et à
 *       laisser le fichier attendu toujours absent.</li>
 * </ol>
 *
 * <p>Les deux se voyaient uniquement hors conteneur : Compose et k8s renseignent
 * {@code SPECTRA_LLM_CHAT_FILE}, ce qui court-circuite le repli fautif. C'est exactement le
 * genre de défaut qui revient sans bruit — d'où ces tests.
 */
class StartupOrchestratorTest {

    private static final String DEFAULT_CHAT_FILE = "Qwen2.5-7B-Instruct-Q4_K_M.gguf";
    private static final String DEFAULT_CHAT_REPO = "bartowski/Qwen2.5-7B-Instruct-GGUF";

    /** Propriétés dont seul le fichier de chat varie ; {@code null} = section absente. */
    private static SpectraProperties propsWithChatFile(String chatFile) {
        SpectraProperties props = mock(SpectraProperties.class);
        SpectraProperties.EndpointProperties chat = chatFile == null
                ? null
                : new SpectraProperties.EndpointProperties(null, null, chatFile, null);
        SpectraProperties.LlmProperties llm = new SpectraProperties.LlmProperties(
                null, null, null, null, null, chat, null, null);
        org.mockito.Mockito.when(props.llm()).thenReturn(llm);
        return props;
    }

    private static StartupOrchestrator orchestrator(SpectraProperties props,
                                                    LlmFitService llmFit,
                                                    boolean autoInstall) {
        StartupOrchestrator orchestrator = new StartupOrchestrator(props, llmFit);
        ReflectionTestUtils.setField(orchestrator, "autoInstallModels", autoInstall);
        return orchestrator;
    }

    // ── Défaut n°1 : un alias n'est pas un nom de fichier ────────────────────────

    @Test
    @DisplayName("chat.file absent : le repli rend un NOM DE FICHIER, pas l'alias du modèle")
    void repliChatRendUnNomDeFichier() {
        String resolved = orchestrator(propsWithChatFile(null), mock(LlmFitService.class), true)
                .resolveChatModelFile();

        assertThat(resolved).isEqualTo(DEFAULT_CHAT_FILE);
        assertThat(resolved).endsWith(".gguf");
        // L'alias que rendait l'ancien repli — un chemin bâti dessus ne désigne rien.
        assertThat(resolved).isNotEqualTo("qwen2.5-7b-instruct");
    }

    @Test
    @DisplayName("embedding.file absent : même garantie, un fichier et non 'nomic-embed-text'")
    void repliEmbeddingRendUnNomDeFichier() {
        String resolved = orchestrator(propsWithChatFile(null), mock(LlmFitService.class), true)
                .resolveEmbeddingModelFile();

        assertThat(resolved).isEqualTo("embed.gguf");
        assertThat(resolved).isNotEqualTo("nomic-embed-text");
    }

    @Test
    @DisplayName("chat.file renseigné : il est respecté tel quel")
    void fichierConfigureRespecte() {
        String resolved = orchestrator(propsWithChatFile("Phi-4-mini-reasoning-UD-IQ1_S.gguf"),
                mock(LlmFitService.class), true).resolveChatModelFile();

        assertThat(resolved).isEqualTo("Phi-4-mini-reasoning-UD-IQ1_S.gguf");
    }

    // ── Défaut n°2 : n'installer que ce que le rattrapage sait produire ──────────

    @Test
    @DisplayName("modèle configuré ≠ défaut et absent : AUCUNE installation")
    void modeleNonDefautAbsentNInstalleRien() {
        LlmFitService llmFit = mock(LlmFitService.class);

        orchestrator(propsWithChatFile("Phi-4-mini-reasoning-UD-IQ1_S.gguf"), llmFit, true)
                .checkAndInstallMissingModels();

        // Installer ici téléchargerait plusieurs Go de Qwen — un AUTRE modèle — et
        // l'activerait par-dessus le choix de l'utilisateur, sans que Phi-4 cesse de manquer.
        verify(llmFit, never()).installModel(anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("modèle par défaut absent : installation du dépôt par défaut, une seule fois")
    void modeleDefautAbsentDeclencheLInstallation() {
        LlmFitService llmFit = mock(LlmFitService.class);

        orchestrator(propsWithChatFile(DEFAULT_CHAT_FILE), llmFit, true)
                .checkAndInstallMissingModels();

        verify(llmFit, times(1)).installModel(eq(DEFAULT_CHAT_REPO), eq("Q4_K_M"), eq(true));
    }

    @Test
    @DisplayName("auto-install-models=false : rien n'est installé, même le modèle par défaut")
    void interrupteurCoupeLeRattrapage() {
        LlmFitService llmFit = mock(LlmFitService.class);

        orchestrator(propsWithChatFile(DEFAULT_CHAT_FILE), llmFit, false)
                .checkAndInstallMissingModels();

        verify(llmFit, never()).installModel(any(), any(), anyBoolean());
    }
}
