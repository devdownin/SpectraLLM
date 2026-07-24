package fr.spectra.controller;

import fr.spectra.dto.QualityBenchmarkReport;
import fr.spectra.dto.QualityCompareJob;
import fr.spectra.service.QualityBenchmarkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
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

    @PostMapping("/compare/async")
    @Operation(
            summary = "Lancer une comparaison qualité asynchrone (suivie)",
            description = "Boucle « comparatif → qualité mesurée » : après installation + activation "
                    + "d'un modèle, compare le nouveau (candidate) au précédent (baseline) sur votre "
                    + "corpus. Non bloquant (le benchmark est lent sur CPU) : renvoie un jobId à suivre "
                    + "via GET /compare/{jobId}. Une seule comparaison à la fois (409 sinon)."
    )
    public ResponseEntity<Map<String, String>> compareAsync(
            @RequestParam String baseline,
            @RequestParam String candidate) {
        String jobId = service.submitCompare(baseline, candidate);
        if (jobId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Une comparaison qualité est déjà en cours"));
        }
        return ResponseEntity.ok(Map.of("jobId", jobId, "status", "PENDING"));
    }

    @GetMapping("/compare/{jobId}")
    @Operation(summary = "Suivi d'une comparaison qualité asynchrone")
    public QualityCompareJob getCompareJob(@PathVariable String jobId) {
        QualityCompareJob job = service.getCompareJob(jobId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comparaison inconnue: " + jobId);
        }
        return job;
    }

    @GetMapping("/compare")
    @Operation(summary = "Historique des comparaisons qualité (les plus récentes d'abord)")
    public List<QualityCompareJob> listCompareJobs() {
        return service.getCompareJobs();
    }

    @DeleteMapping("/compare/{jobId}")
    @Operation(
            summary = "Annuler une comparaison qualité en cours",
            description = "Annulation coopérative (même convention que DELETE /api/ablation/jobs/"
                    + "{jobId}) : prise en compte entre deux questions du benchmark ; l'appel LLM "
                    + "en cours se termine d'abord. 409 si le job est déjà terminé.")
    public ResponseEntity<Map<String, String>> cancelCompareJob(@PathVariable String jobId) {
        if (service.getCompareJob(jobId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comparaison inconnue: " + jobId);
        }
        if (!service.requestCancelCompare(jobId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Le job est déjà terminé"));
        }
        return ResponseEntity.ok(Map.of("jobId", jobId, "status", "CANCELLING"));
    }
}
