package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
}
