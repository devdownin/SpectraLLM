package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.dto.LlmFitRecommendation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sécurité : validation des arguments passés au sous-processus {@code llmfit}.
 *
 * <p>{@code ProcessBuilder(List)} n'utilise pas de shell, mais un argument commençant
 * par « - » serait interprété par llmfit comme une option (argument injection). Ces
 * valeurs venant de requêtes HTTP, {@code installModel} doit rejeter toute entrée hors
 * allowlist <b>avant</b> de démarrer le processus.
 */
class LlmFitServiceTest {

    private LlmFitService newService() {
        return new LlmFitService(
                new ObjectMapper(),
                mock(ModelRegistryService.class),
                mock(LlmChatClient.class));
    }

    // ── Entrées rejetées (aucun sous-processus ne doit démarrer) ────────────────

    @Test
    void installModel_leadingDashModelName_isRejected() {
        assertThatThrownBy(() -> newService().installModel("--output=/etc/passwd", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelName");
    }

    @Test
    void installModel_shellMetacharsInModelName_isRejected() {
        assertThatThrownBy(() -> newService().installModel("model; rm -rf /", null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void installModel_nullModelName_isRejected() {
        assertThatThrownBy(() -> newService().installModel(null, null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void installModel_leadingDashQuant_isRejected() {
        assertThatThrownBy(() -> newService().installModel("valid-model", "--evil", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quant");
    }

    // ── Entrées légitimes acceptées (pas d'exception de validation) ─────────────

    @Test
    void installModel_huggingFaceStyleName_isAccepted() {
        // Ne doit PAS lever d'IllegalArgumentException ; renvoie un futur (l'exécution
        // asynchrone de llmfit échouera hors CI mais n'affecte pas la validation synchrone).
        assertThat(newService().installModel("TheBloke/Llama-2-7B-GGUF", "Q4_K_M", false)).isNotNull();
    }

    @Test
    void installModel_ollamaStyleName_isAccepted() {
        assertThat(newService().installModel("llama3.2:3b", null, false)).isNotNull();
    }

    // ── Recommandations : validation du simulateur et croisement registre ───────

    @Test
    void getRecommendations_simulationInvalide_rejetteEn400AuLieuDeListeVide() {
        // « 12 G » (espace) sort de l'allowlist : l'utilisateur doit voir une 400 claire,
        // pas un Model Hub silencieusement vide.
        assertThatThrownBy(() -> newService().getRecommendations(10, "12 G", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memory");
    }

    @Test
    void enrichWithRegistry_modeleEnregistreDansSpectra_marqueInstalled() {
        ModelRegistryService registry = mock(ModelRegistryService.class);
        when(registry.listModels("chat")).thenReturn(List.of(
                Map.of("name", "llama3.2-3b-q4", "hfRepo", "llama3.2:3b")));
        LlmFitService service = new LlmFitService(new ObjectMapper(), registry, mock(LlmChatClient.class));

        LlmFitRecommendation.ModelRecommendation installedViaSpectra = recommendation("llama3.2:3b", false);
        LlmFitRecommendation.ModelRecommendation notInstalled = recommendation("qwen2.5:7b", false);

        LlmFitRecommendation enriched = service.enrichWithRegistry(
                new LlmFitRecommendation(List.of(installedViaSpectra, notInstalled), null));

        assertThat(enriched.models().get(0).installed())
                .as("modèle présent dans le registre Spectra (hfRepo) → installé")
                .isTrue();
        assertThat(enriched.models().get(1).installed()).isFalse();
    }

    private static LlmFitRecommendation.ModelRecommendation recommendation(String name, boolean installed) {
        return new LlmFitRecommendation.ModelRecommendation(
                name, "provider", "3B", "chat", 90.0, "Good", "Q4_K_M",
                12.0, 3.5, 2.1, 4096, List.of(), Map.of(), List.of(), installed, "CPU");
    }

    // ── Suivi de progression (SSE) ───────────────────────────────────────────────

    @Test
    void getInstallationProgress_telechargementInconnu_fluxCompleteImmediatement() {
        // Reprise d'UI après redémarrage de l'API : aucun sink pour ce modèle.
        // Le flux doit se terminer (l'EventSource client se ferme) au lieu de rester muet.
        assertThat(newService().getInstallationProgress("inconnu")
                .collectList()
                .block(Duration.ofSeconds(2)))
                .isEmpty();
    }

    // ── Repli de détection du GGUF téléchargé (scan de models-dir) ──────────────

    @TempDir
    Path modelsDir;

    @Test
    void findRecentGguf_retourneLeGgufApparuDepuisLeDebutDuTelechargement() throws Exception {
        Instant start = Instant.now().minusSeconds(10);
        Path gguf = Files.writeString(modelsDir.resolve("nouveau-modele.gguf"), "fake");
        Files.writeString(modelsDir.resolve("autre-fichier.txt"), "pas un gguf");

        assertThat(LlmFitService.findRecentGguf(modelsDir, start)).contains(gguf);
    }

    @Test
    void findRecentGguf_ignoreLesGgufAnterieursAuTelechargement() throws Exception {
        Path ancien = Files.writeString(modelsDir.resolve("ancien.gguf"), "fake");
        Files.setLastModifiedTime(ancien,
                java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(3600)));

        assertThat(LlmFitService.findRecentGguf(modelsDir, Instant.now().minusSeconds(10))).isEmpty();
    }

    @Test
    void findRecentGguf_repertoireInexistant_retourneVide() {
        assertThat(LlmFitService.findRecentGguf(modelsDir.resolve("absent"), Instant.now())).isEmpty();
    }
}
