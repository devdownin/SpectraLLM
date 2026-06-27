package fr.spectra.controller;

import fr.spectra.dto.QualityBenchmarkReport;
import fr.spectra.service.QualityBenchmarkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Évaluation qualité sur un benchmark tenu à l'écart (exactitude + taux d'hallucination),
 * avec comparaison base vs fine-tuné. Distinct de {@code /api/benchmark} (performance/latence).
 *
 * <p><b>Avertissement :</b> endpoints bloquants (plusieurs appels LLM par question), lents sur CPU.</p>
 */
@RestController
@RequestMapping("/api/quality-benchmark")
@Tag(name = "Quality Benchmark", description = "Évaluation qualité tenue à l'écart (exactitude, hallucination)")
public class QualityBenchmarkController {

    private final QualityBenchmarkService service;

    public QualityBenchmarkController(QualityBenchmarkService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(
            summary = "Lancer le benchmark qualité sur un modèle",
            description = "Évalue le modèle sur le jeu de référence tenu à l'écart : score d'exactitude "
                    + "(questions answerable) et taux d'hallucination (questions sans réponse dans le corpus). "
                    + "Paramètre 'model' optionnel : bascule temporairement le modèle actif puis le restaure "
                    + "(permet d'évaluer un modèle précis). Bloquant, lent sur CPU."
    )
    public QualityBenchmarkReport run(@RequestParam(required = false) String model) {
        return service.run(model);
    }

    @PostMapping("/compare")
    @Operation(
            summary = "Comparer deux modèles (base vs fine-tuné)",
            description = "Exécute le benchmark sur 'baseline' puis 'candidate' et retourne les deux rapports. "
                    + "Permet de quantifier l'effet du fine-tuning (exactitude et hallucination avant/après)."
    )
    public Map<String, QualityBenchmarkReport> compare(
            @RequestParam String baseline,
            @RequestParam String candidate) {
        return service.compare(baseline, candidate);
    }
}
