package fr.spectra.controller;

import fr.spectra.dto.EvaluationReport;
import fr.spectra.dto.EvaluationRequest;
import fr.spectra.service.EvaluationService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

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

    @GetMapping("/{evalId}")
    public ResponseEntity<EvaluationReport> getReport(@PathVariable String evalId) {
        EvaluationReport report = evaluationService.getReport(evalId);
        return report != null ? ResponseEntity.ok(report) : ResponseEntity.notFound().build();
    }

    @GetMapping
    public List<EvaluationReport> getAllReports() {
        return evaluationService.getAllReports();
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
