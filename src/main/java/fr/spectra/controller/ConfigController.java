package fr.spectra.controller;

import fr.spectra.dto.ResourceProfile;
import fr.spectra.service.EmbeddingConsistencyChecker;
import fr.spectra.service.EmbeddingReindexService;
import fr.spectra.service.LlmChatClient;
import fr.spectra.service.ResourceAdvisorService;
import fr.spectra.service.RuntimeParamsMaterializer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    private final EmbeddingConsistencyChecker embeddingConsistencyChecker;
    private final EmbeddingReindexService embeddingReindexService;
    private final RuntimeParamsMaterializer runtimeParamsMaterializer;

    public ConfigController(LlmChatClient chatClient, ResourceAdvisorService resourceAdvisor,
                            EmbeddingConsistencyChecker embeddingConsistencyChecker,
                            EmbeddingReindexService embeddingReindexService,
                            RuntimeParamsMaterializer runtimeParamsMaterializer) {
        this.chatClient = chatClient;
        this.resourceAdvisor = resourceAdvisor;
        this.embeddingConsistencyChecker = embeddingConsistencyChecker;
        this.embeddingReindexService = embeddingReindexService;
        this.runtimeParamsMaterializer = runtimeParamsMaterializer;
    }

    @Operation(summary = "Retourne le modèle LLM actif")
    @GetMapping("/model")
    public ResponseEntity<Map<String, String>> getModel() {
        return ResponseEntity.ok(Map.of("model", chatClient.getActiveModel()));
    }

    @Operation(summary = "Liste les modèles de chat enregistrés (registre local)")
    @GetMapping("/models")
    public ResponseEntity<List<Map<String, Object>>> listModels() {
        return ResponseEntity.ok(chatClient.listModels());
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
        } catch (IllegalArgumentException | IllegalStateException e) {
            // IllegalArgumentException : alias inconnu du registre (rejeté sans créer d'entrée) ;
            // IllegalStateException : modèle connu mais non servisable (orchestrateur runtime).
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of(
                "model", model,
                "status", "updated"
        ));
    }

    @Operation(
            summary = "Cohérence entre le modèle d'embedding actif et les index vectoriels",
            description = "Compare l'estampille 'spectra:embedding-model' de chaque collection "
                    + "ChromaDB au modèle d'embedding actif. Une collection MISMATCH a été indexée "
                    + "avec un autre modèle : ses vecteurs ne sont pas comparables aux nouveaux "
                    + "embeddings et le RAG dessus est bloqué tant qu'elle n'est pas ré-ingérée "
                    + "(ou que le modèle d'origine n'est pas réactivé). UNSTAMPED = collection "
                    + "créée avant cette protection, cohérence invérifiable."
    )
    @GetMapping("/embedding-consistency")
    public ResponseEntity<Map<String, Object>> embeddingConsistency() {
        return ResponseEntity.ok(embeddingConsistencyChecker.verify());
    }

    @Operation(
            summary = "Ré-indexe une collection avec le modèle d'embedding actif",
            description = "Remédiation d'un statut MISMATCH : recalcule les vecteurs de tous les "
                    + "chunks (textes et métadonnées conservés, pas de ré-ingestion des fichiers) "
                    + "puis ré-estampille la collection. La collection reste bloquée pour le RAG "
                    + "pendant l'opération (l'estampille n'est mise à jour qu'à la fin). "
                    + "Une seule ré-indexation à la fois — 409 si une autre est en cours. "
                    + "Suivi via GET /api/config/embedding-consistency/reindex."
    )
    @PostMapping("/embedding-consistency/reindex")
    public ResponseEntity<Map<String, Object>> reindexCollection(@RequestBody Map<String, String> body) {
        String collection = body.get("collection");
        try {
            return ResponseEntity.accepted().body(embeddingReindexService.start(collection).toApi());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @Operation(summary = "Statut des ré-indexations (en cours et terminées)")
    @GetMapping("/embedding-consistency/reindex")
    public ResponseEntity<List<Map<String, Object>>> reindexStatuses() {
        return ResponseEntity.ok(embeddingReindexService.statuses().stream()
                .map(EmbeddingReindexService.ReindexStatus::toApi)
                .toList());
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
        ResourceProfile profile = resourceAdvisor.refresh();
        // Répercute les nouvelles recommandations vers llm-chat (fichier de hints) :
        // elles seront appliquées à son prochain (re)démarrage de modèle.
        runtimeParamsMaterializer.materialize();
        return profile;
    }
}
