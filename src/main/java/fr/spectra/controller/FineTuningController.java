package fr.spectra.controller;

import fr.spectra.dto.FineTuningJob;
import fr.spectra.dto.FineTuningRequest;
import fr.spectra.dto.ModelRegistrationRequest;
import fr.spectra.service.BaseModelCatalog;
import fr.spectra.service.FineTuningService;
import fr.spectra.service.LlmChatClient;
import fr.spectra.service.ModelRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/fine-tuning")
@Tag(name = "Fine-tuning", description = "Pilotage du fine-tuning LoRA/QLoRA")
public class FineTuningController {

    private final FineTuningService fineTuningService;
    private final LlmChatClient llmClient;
    private final BaseModelCatalog baseModelCatalog;
    private final ModelRegistryService modelRegistry;

    public FineTuningController(FineTuningService fineTuningService, LlmChatClient llmClient,
                                BaseModelCatalog baseModelCatalog, ModelRegistryService modelRegistry) {
        this.fineTuningService = fineTuningService;
        this.llmClient = llmClient;
        this.baseModelCatalog = baseModelCatalog;
        this.modelRegistry = modelRegistry;
    }

    @PostMapping("/models/register")
    @Operation(summary = "Enregistrer un modèle (GGUF) dans le registre local",
            description = "Point d'entrée pour rendre un modèle téléchargé ou converti "
                    + "manuellement visible et activable par Spectra (c'est la commande "
                    + "affichée en fin d'export_gguf.py). Les champs hfRepo/quantization/"
                    + "contextLength sont optionnels et servent à la traçabilité.")
    public ResponseEntity<Map<String, Object>> registerModel(
            @Valid @RequestBody ModelRegistrationRequest request) {
        modelRegistry.registerModel(request.name(), request.type(), request.source(),
                request.systemPrompt(), request.parameters(), "api", false,
                new ModelRegistryService.ModelOrigin(
                        request.hfRepo(), request.quantization(), request.contextLength()));

        if (Boolean.TRUE.equals(request.activate())) {
            if ("embedding".equals(request.type())) {
                modelRegistry.setActiveEmbeddingModel(request.name());
            } else {
                // Via le chat client : registre + orchestrateur runtime + état en mémoire.
                llmClient.setActiveModel(request.name());
            }
        }

        return ResponseEntity.ok(Map.of(
                "name", request.name(),
                "type", request.type(),
                "status", "registered",
                "activated", Boolean.TRUE.equals(request.activate())
        ));
    }

    @GetMapping("/base-models")
    @Operation(summary = "Catalogue des modèles de base entraînables (alias → repo HuggingFace)",
            description = "Source de vérité unique (base_models.json), partagée avec les scripts "
                    + "d'entraînement et de fusion. Le champ baseModel d'un job accepte un alias "
                    + "de ce catalogue ou un repo HuggingFace complet (« org/nom »).")
    public List<Map<String, Object>> listBaseModels() {
        return baseModelCatalog.list();
    }

    @PostMapping
    @Operation(summary = "Lancer un job de fine-tuning QLoRA")
    public ResponseEntity<Map<String, String>> startFineTuning(@Valid @RequestBody FineTuningRequest request) {
        String jobId = fineTuningService.submit(request);
        if (jobId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Un entraînement est déjà en cours"));
        }
        return ResponseEntity.ok(Map.of("jobId", jobId, "status", "PENDING"));
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "Suivi d'un job de fine-tuning (progression, epoch, loss)")
    public FineTuningJob getJobStatus(@PathVariable String jobId) {
        FineTuningJob job = fineTuningService.getJob(jobId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job inconnu: " + jobId);
        }
        return job;
    }

    @GetMapping
    @Operation(summary = "Lister tous les jobs de fine-tuning")
    public List<FineTuningJob> listJobs() {
        return fineTuningService.getAllJobs();
    }

    @GetMapping("/models")
    @Operation(summary = "Lister les modèles disponibles sur le serveur LLM")
    public List<Map<String, Object>> listModels() {
        return llmClient.listModels();
    }

    @DeleteMapping("/models/{name}")
    @Operation(summary = "Retirer un modèle du registre (suppression optionnelle du GGUF)",
            description = "Le modèle ACTIF n'est pas supprimable (409) : activez-en un autre "
                    + "d'abord. Avec deleteFile=true, le fichier GGUF est supprimé seulement "
                    + "s'il réside dans le répertoire des modèles et n'est référencé par aucun "
                    + "autre modèle enregistré (sinon fileSkippedReason l'explique).")
    public ResponseEntity<Map<String, Object>> deleteModel(
            @PathVariable String name,
            @RequestParam(defaultValue = "chat") String type,
            @RequestParam(defaultValue = "false") boolean deleteFile) {
        try {
            return ResponseEntity.ok(modelRegistry.removeModel(name, type, deleteFile));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @DeleteMapping("/{jobId}")
    @Operation(summary = "Annuler un job de fine-tuning en cours")
    public ResponseEntity<Map<String, String>> cancelJob(@PathVariable String jobId) {
        boolean cancelled = fineTuningService.cancelJob(jobId);
        if (!cancelled) {
            FineTuningJob job = fineTuningService.getJob(jobId);
            if (job == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job inconnu: " + jobId);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Impossible d'annuler (status=" + job.status() + ")");
        }
        return ResponseEntity.ok(Map.of("jobId", jobId, "status", "CANCELLED"));
    }
}
