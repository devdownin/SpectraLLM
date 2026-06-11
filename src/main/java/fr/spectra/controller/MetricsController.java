package fr.spectra.controller;

import fr.spectra.service.PersonalizationMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Métriques du cycle de personnalisation continue.
 *
 * <pre>
 * GET /api/metrics/personalization — Agrégat commentaires → DPO → fine-tuning → évaluation
 * </pre>
 */
@RestController
@RequestMapping("/api/metrics")
@Tag(name = "Metrics", description = "Métriques du cycle de personnalisation LLM")
public class MetricsController {

    private final PersonalizationMetricsService metricsService;

    public MetricsController(PersonalizationMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/personalization")
    @Operation(summary = "Métriques complètes du cycle de personnalisation",
               description = "Retourne l'état de la boucle commentaires → DPO → fine-tuning → évaluation")
    public PersonalizationMetricsService.PersonalizationMetrics getPersonalizationMetrics() {
        return metricsService.getMetrics();
    }
}
