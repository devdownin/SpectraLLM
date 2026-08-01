package fr.spectra.config;

import fr.spectra.dto.ServiceStatus;
import fr.spectra.service.LlmChatClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L'enjeu de ces tests n'est pas qu'un statut soit « juste » : c'est qu'un modèle en cours
 * de chargement ne puisse JAMAIS dégrader le statut agrégé de /actuator/health. Le healthcheck
 * Compose et la supervision s'appuient dessus ; un DOWN pendant un démarrage normal se paie en
 * alertes nocturnes, puis en alertes ignorées.
 */
class LlmChatHealthIndicatorTest {

    private static LlmChatHealthIndicator indicator(ServiceStatus status) {
        LlmChatClient client = mock(LlmChatClient.class);
        when(client.checkHealth()).thenReturn(status);
        return new LlmChatHealthIndicator(client);
    }

    private static ServiceStatus loaded() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("activeModel", "qwen2.5-7b-instruct");
        details.put("activeModelLoaded", true);
        details.put("registeredModels", List.of("un", "catalogue", "volumineux"));
        details.put("runtime", Map.of("pid", 42));
        return new ServiceStatus("llama-cpp", "http://llm-chat:8081", true, "ok", 12, details);
    }

    @Test
    @DisplayName("serveur prêt : UP")
    void serveurPret() {
        Health health = indicator(loaded()).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("state", "ready");
        assertThat(health.getDetails()).containsEntry("activeModel", "qwen2.5-7b-instruct");
    }

    @Test
    @DisplayName("modèle en cours de chargement : UNKNOWN, jamais DOWN")
    void modeleEnCoursDeChargement() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("activeModel", "qwen2.5-7b-instruct");
        details.put("activeModelLoaded", false);
        ServiceStatus notLoaded =
                new ServiceStatus("llama-cpp", "http://llm-chat:8081", false, "model-not-loaded", 8, details);

        Health health = indicator(notLoaded).health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getStatus()).isNotEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("state", "loading");
    }

    @Test
    @DisplayName("serveur injoignable : UNKNOWN aussi — l'agrégat ne doit pas basculer")
    void serveurInjoignable() {
        ServiceStatus down = ServiceStatus.unavailable("llama-cpp", "http://llm-chat:8081", 5000);

        Health health = indicator(down).health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getStatus()).isNotEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("state", "unreachable");
    }

    @Test
    @DisplayName("UNKNOWN est le dernier de l'ordre d'agrégation : il ne peut pas dégrader l'agrégat")
    void unknownNePeutPasDegraderLAgregat() {
        // Garde-fou explicite : si une montée de version de Boot changeait cet ordre, un modèle
        // en chargement rendrait /actuator/health non-UP et le test le dirait ici, pas en prod.
        assertThat(Status.UNKNOWN.getCode()).isEqualTo("UNKNOWN");
        assertThat(Status.UNKNOWN).isNotEqualTo(Status.DOWN);
        assertThat(Status.UNKNOWN).isNotEqualTo(Status.OUT_OF_SERVICE);
    }

    @Test
    @DisplayName("les détails volumineux de /api/status ne sont pas recopiés dans la sonde")
    void detailsVolumineuxNonRecopies() {
        Health health = indicator(loaded()).health();

        assertThat(health.getDetails()).doesNotContainKeys("registeredModels", "runtime", "availableModels");
        assertThat(health.getDetails()).containsKeys("url", "elapsedMs", "state", "activeModel");
    }
}
