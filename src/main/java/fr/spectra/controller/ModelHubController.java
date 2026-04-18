package fr.spectra.controller;

import fr.spectra.dto.LlmFitRecommendation;
import fr.spectra.service.LlmFitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/api/models/hub")
@Tag(name = "Model Hub", description = "Découverte et installation de modèles via llmfit")
public class ModelHubController {

    private final LlmFitService llmFitService;

    public ModelHubController(LlmFitService llmFitService) {
        this.llmFitService = llmFitService;
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
    public Map<String, String> installModel(@RequestParam String modelName,
                                           @RequestParam(required = false) String quant) {
        llmFitService.installModel(modelName, quant, false);
        return Map.of("status", "IN_PROGRESS", "modelName", modelName);
    }
}
