package fr.spectra.controller;

import fr.spectra.dto.ResourceProfile;
import fr.spectra.service.LlmChatClient;
import fr.spectra.service.ResourceAdvisorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Permet de lire et modifier à chaud le modèle LLM actif (chat / RAG / dataset).
 * Utile après un fine-tuning pour basculer vers le nouveau modèle sans redémarrer.
 */
@RestController
@RequestMapping("/api/config")
@Tag(name = "Configuration", description = "Paramétrage à chaud du modèle actif")
public class ConfigController {

    private final LlmChatClient chatClient;
    private final ResourceAdvisorService resourceAdvisor;

    public ConfigController(LlmChatClient chatClient, ResourceAdvisorService resourceAdvisor) {
        this.chatClient = chatClient;
        this.resourceAdvisor = resourceAdvisor;
    }

    @Operation(summary = "Retourne le modèle LLM actif")
    @GetMapping("/model")
    public ResponseEntity<Map<String, String>> getModel() {
        return ResponseEntity.ok(Map.of("model", chatClient.getActiveModel()));
    }

    @Operation(summary = "Change le modèle LLM actif (chat, RAG, génération dataset)")
    @PostMapping("/model")
    public ResponseEntity<Map<String, String>> setModel(@RequestBody Map<String, String> body) {
        String model = body.get("model");
        if (model == null || model.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Le champ 'model' est requis"));
        }
        try {
            chatClient.setActiveModel(model);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of(
                "model", model,
                "status", "updated"
        ));
    }

    @Operation(
            summary = "Profil de ressources et paramètres recommandés",
            description = "Détecte CPU, RAM et GPU disponibles, et retourne les paramètres "
                    + "llama-server recommandés pour chaque mode (chat, embed). "
                    + "Utile pour diagnostiquer une configuration sous-optimale ou valider "
                    + "les paramètres appliqués par llama-autostart.sh. "
                    + "Appeler POST /api/config/resources/refresh pour forcer une nouvelle détection."
    )
    @GetMapping("/resources")
    public ResourceProfile getResources() {
        return resourceAdvisor.getProfile();
    }

    @Operation(
            summary = "Recalcule le profil de ressources",
            description = "Force une nouvelle détection CPU/RAM/GPU et met à jour le cache. "
                    + "Utile si les ressources ont changé (ex. GPU ajouté à chaud, limite cgroup modifiée)."
    )
    @PostMapping("/resources/refresh")
    public ResourceProfile refreshResources() {
        return resourceAdvisor.refresh();
    }
}
