package fr.spectra.controller;

import fr.spectra.dto.FineTuningJob;
import fr.spectra.dto.FineTuningRequest;
import fr.spectra.dto.ModelRegistrationRequest;
import fr.spectra.service.BaseModelCatalog;
import fr.spectra.service.FineTuningService;
import fr.spectra.service.JobTelemetryStore;
import fr.spectra.service.LlmChatClient;
import fr.spectra.service.ModelRegistryService;
import fr.spectra.service.training.TrainingRunner;
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
    private final JobTelemetryStore telemetryStore;
    private final TrainingRunner trainingRunner;

    public FineTuningController(FineTuningService fineTuningService, LlmChatClient llmClient,
                                BaseModelCatalog baseModelCatalog, ModelRegistryService modelRegistry,
                                JobTelemetryStore telemetryStore, TrainingRunner trainingRunner) {
        this.fineTuningService = fineTuningService;
        this.llmClient = llmClient;
        this.baseModelCatalog = baseModelCatalog;
        this.modelRegistry = modelRegistry;
        this.telemetryStore = telemetryStore;
        this.trainingRunner = trainingRunner;
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
    @Operation(summary = "Lancer un job de fine-tuning QLoRA",
            description = "Rend le job COMPLET, et non le seul identifiant : l'appelant peut "
                    + "afficher son suivi immédiatement, sans attendre le premier sondage.")
    public ResponseEntity<FineTuningJob> startFineTuning(@Valid @RequestBody FineTuningRequest request) {
        String jobId = fineTuningService.submit(request);
        if (jobId == null) {
            // ProblemDetail (via ResponseStatusException) et non une Map ad hoc : le motif voyage
            // alors dans « detail », le champ que lisent les clients de l'API — sinon l'UI n'avait
            // que « Request failed with status code 409 » à afficher.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Un entraînement est déjà en cours — un seul job à la fois. "
                            + "Attendez sa fin ou annulez-le, puis relancez.");
        }
        // Le job vient d'être créé ; s'il avait déjà disparu, un 404 serait plus honnête qu'une
        // réponse partielle.
        FineTuningJob job = fineTuningService.getJob(jobId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job inconnu: " + jobId);
        }
        return ResponseEntity.ok(job);
    }

    @GetMapping("/availability")
    @Operation(summary = "L'entraînement est-il exécutable sur cette installation ?",
            description = "La disponibilité de l'exécuteur n'était constatée qu'à la soumission : "
                    + "l'utilisateur remplissait un formulaire, choisissait ses hyperparamètres, "
                    + "lançait, et découvrait alors que le profil « trainer » n'était pas démarré. "
                    + "Le motif nomme la commande à lancer.")
    public Map<String, Object> getAvailability() {
        String reason = trainingRunner.unavailabilityReason();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("available", reason == null);
        body.put("reason", reason);
        return body;
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

    @GetMapping("/{jobId}/telemetry")
    @Operation(summary = "Trace persistée d'un job : journal de sortie et série de perte",
            description = "Le flux SSE n'a pas de mémoire — il ne rejoue rien à un client qui se "
                    + "connecte en cours de route, et rien du tout pour un job terminé. Cet "
                    + "endpoint rend la trace écrite sur disque pendant l'exécution, ce qui "
                    + "permet à l'interface de retrouver logs et courbe après un rechargement. "
                    + "Un job sans trace (antérieur, ou dont le répertoire a été purgé) rend une "
                    + "trace vide, pas une erreur.")
    public JobTelemetryStore.Telemetry getJobTelemetry(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "500") int tail) {
        // Le job doit exister : cela borne l'identifiant aux valeurs que le service a produites,
        // et distingue « job inconnu » (404) de « job sans trace » (trace vide).
        if (fineTuningService.getJob(jobId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job inconnu: " + jobId);
        }
        // Borne haute : un journal peut compter des dizaines de milliers de lignes, et les
        // renvoyer toutes ferait payer au navigateur ce que personne ne lira.
        return telemetryStore.read(jobId, Math.clamp(tail, 1, 5_000));
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
