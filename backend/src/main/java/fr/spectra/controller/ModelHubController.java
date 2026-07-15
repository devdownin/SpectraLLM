package fr.spectra.controller;

import fr.spectra.dto.InstallationJob;
import fr.spectra.dto.LlmFitRecommendation;
import fr.spectra.service.LlmFitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(value = "/api/models/hub", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Model Hub", description = "Découverte et installation de modèles via llmfit")
public class ModelHubController {

    private final LlmFitService llmFitService;

    public ModelHubController(LlmFitService llmFitService) {
        this.llmFitService = llmFitService;
    }

    @GetMapping("/storage")
    @Operation(summary = "Inventaire du volume des modèles (GGUF, tailles, alias, actifs)",
            description = "Chaque fichier GGUF de data/models/ avec sa taille, les modèles du "
                    + "registre qui le référencent et son statut actif. Complète le cycle de "
                    + "vie : suppression via DELETE /api/fine-tuning/models/{name}?deleteFile=true.")
    public Map<String, Object> getStorage() {
        return llmFitService.getStorageReport();
    }

    @GetMapping("/recommendations")
    @Operation(summary = "Récupérer les 10 meilleurs modèles optimisés pour le matériel (avec simulation optionnelle)")
    public LlmFitRecommendation getRecommendations(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String memory,
            @RequestParam(required = false) String ram,
            @RequestParam(required = false) Integer cpuCores) {
        return llmFitService.getRecommendations(limit, memory, ram, cpuCores);
    }

    @PostMapping("/install")
    @Operation(summary = "Lancer le téléchargement et l'installation d'un modèle")
    public Map<String, String> installModel(
            @RequestParam String modelName,
            @RequestParam(required = false) String quant,
            @RequestParam(defaultValue = "false") boolean autoActivate) {
        llmFitService.installModel(modelName, quant, autoActivate);
        return Map.of("status", "IN_PROGRESS", "modelName", modelName);
    }

    @GetMapping("/installations")
    @Operation(summary = "Historique des installations (persisté, survit au redémarrage de l'API)",
            description = "Chaque téléchargement lancé via le Model Hub avec son statut "
                    + "(DOWNLOADING/COMPLETED/FAILED), sa progression et son éventuelle erreur. "
                    + "Un téléchargement interrompu par un redémarrage apparaît en FAILED plutôt "
                    + "que figé — la réconciliation au démarrage le réconcilie.")
    public List<InstallationJob> listInstallations() {
        return llmFitService.getInstallations();
    }

    @GetMapping("/installations/{jobId}")
    @Operation(summary = "Suivi d'une installation par identifiant")
    public InstallationJob getInstallation(@PathVariable String jobId) {
        InstallationJob job = llmFitService.getInstallation(jobId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Installation inconnue: " + jobId);
        }
        return job;
    }

    @DeleteMapping("/installations/{jobId}")
    @Operation(summary = "Annuler un téléchargement de modèle en cours",
            description = "Tue le processus llmfit et marque le job CANCELLED. Un fichier "
                    + "partiellement téléchargé est réutilisé au prochain essai. "
                    + "409 si le job est déjà terminé.")
    public Map<String, String> cancelInstallation(@PathVariable String jobId) {
        InstallationJob job = llmFitService.getInstallation(jobId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Installation inconnue: " + jobId);
        }
        if (!llmFitService.cancelInstall(jobId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Impossible d'annuler (status=" + job.status() + ")");
        }
        return Map.of("jobId", jobId, "status", "CANCELLED");
    }

    /**
     * SSE stream of download progress (0–100) for a model being installed.
     * Uses a query parameter for the model name to safely handle names with slashes.
     */
    @GetMapping(value = "/install/progress",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Flux SSE de progression du téléchargement (0–100)")
    public Flux<Integer> getInstallationProgress(@RequestParam String modelName) {
        return llmFitService.getInstallationProgress(modelName);
    }
}

