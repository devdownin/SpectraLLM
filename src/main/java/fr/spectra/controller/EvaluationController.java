package fr.spectra.controller;

import fr.spectra.dto.AbComparisonReport;
import fr.spectra.dto.AbComparisonRequest;
import fr.spectra.dto.BatchEvaluationRequest;
import fr.spectra.dto.EvaluationReport;
import fr.spectra.dto.EvaluationRequest;
import fr.spectra.dto.ModelComparisonReport;
import fr.spectra.service.EvaluationService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Endpoints d'évaluation LLM-as-a-judge.
 *
 * <pre>
 * POST /api/evaluation           — Lance une évaluation asynchrone
 * GET  /api/evaluation           — Liste tous les rapports
 * GET  /api/evaluation/{evalId}  — Rapport détaillé (avec progression en temps réel)
 * </pre>
 */
@RestController
@RequestMapping("/api/evaluation")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> submit(
            @RequestBody(required = false) EvaluationRequest request) {
        EvaluationRequest req = request != null ? request : new EvaluationRequest(null, null, null);
        String evalId = evaluationService.submit(req);
        return ResponseEntity.accepted().body(Map.of("evalId", evalId, "status", "PENDING"));
    }

    /**
     * Évalue plusieurs modèles sur un même jeu de test, séquentiellement, pour
     * comparer équitablement leurs gains.
     *
     * <pre>POST /api/evaluation/batch  body: {"modelNames":["a","b"],"testSetSize":20}</pre>
     */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> submitBatch(@RequestBody BatchEvaluationRequest request) {
        try {
            List<String> evalIds = evaluationService.submitBatch(
                    request.modelNames(), request.testSetSize());
            return ResponseEntity.accepted().body(Map.of("evalIds", evalIds, "status", "PENDING"));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Comparaison directe A/B (head-to-head) entre deux modèles : un juge désigne
     * la meilleure des deux réponses à chaque paire.
     *
     * <pre>POST /api/evaluation/ab  body: {"modelA":"a","modelB":"b","testSetSize":20}</pre>
     */
    @PostMapping("/ab")
    public ResponseEntity<Map<String, String>> submitAb(@RequestBody AbComparisonRequest request) {
        try {
            String abId = evaluationService.submitAb(
                    request.modelA(), request.modelB(), request.testSetSize());
            return ResponseEntity.accepted().body(Map.of("abId", abId, "status", "PENDING"));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/ab")
    public List<AbComparisonReport> getAllAbReports() {
        return evaluationService.getAllAbReports();
    }

    @GetMapping("/ab/{abId}")
    public ResponseEntity<AbComparisonReport> getAbReport(@PathVariable String abId) {
        AbComparisonReport report = evaluationService.getAbReport(abId);
        return report != null ? ResponseEntity.ok(report) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/ab/{abId}")
    public ResponseEntity<Map<String, String>> cancelAb(@PathVariable String abId) {
        boolean cancelled = evaluationService.cancelAb(abId);
        if (!cancelled) {
            AbComparisonReport report = evaluationService.getAbReport(abId);
            if (report == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comparaison A/B inconnue: " + abId);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Impossible d'annuler (status=" + report.status() + ")");
        }
        return ResponseEntity.ok(Map.of("abId", abId, "status", "CANCELLED"));
    }

    @GetMapping("/{evalId}")
    public ResponseEntity<EvaluationReport> getReport(@PathVariable String evalId) {
        EvaluationReport report = evaluationService.getReport(evalId);
        return report != null ? ResponseEntity.ok(report) : ResponseEntity.notFound().build();
    }

    @GetMapping
    public List<EvaluationReport> getAllReports() {
        return evaluationService.getAllReports();
    }

    /**
     * Compare plusieurs évaluations pour mesurer les gains et différences de
     * performance entre modèles personnalisés.
     *
     * <pre>GET /api/evaluation/compare?evalIds=id1,id2,id3&amp;baseline=id1</pre>
     *
     * @param evalIds  identifiants des évaluations à comparer (séparés par des virgules)
     * @param baseline identifiant de l'évaluation de référence (optionnel ; à défaut,
     *                 le meilleur score global est pris comme référence)
     */
    @GetMapping("/compare")
    public ResponseEntity<ModelComparisonReport> compare(
            @RequestParam List<String> evalIds,
            @RequestParam(required = false) String baseline) {
        try {
            return ResponseEntity.ok(evaluationService.compareReports(evalIds, baseline));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @DeleteMapping("/{evalId}")
    public ResponseEntity<Map<String, String>> cancelEvaluation(@PathVariable String evalId) {
        boolean cancelled = evaluationService.cancelEvaluation(evalId);
        if (!cancelled) {
            EvaluationReport report = evaluationService.getReport(evalId);
            if (report == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Évaluation inconnue: " + evalId);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Impossible d'annuler (status=" + report.status() + ")");
        }
        return ResponseEntity.ok(Map.of("evalId", evalId, "status", "CANCELLED"));
    }
}
