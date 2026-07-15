package fr.spectra.controller;

import fr.spectra.dto.AblationJob;
import fr.spectra.dto.AblationReport;
import fr.spectra.dto.AblationRequest;
import fr.spectra.service.RagAblationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Ablation A/B des enrichissements (RAG, fine-tuning) sur le benchmark tenu à l'écart.
 *
 * <p>Mesure, par configuration (bras), la qualité de génération (exactitude, hallucination), la
 * qualité de retrieval (Hit@k / MRR / Recall@k) et la latence — pour lire le gain marginal de
 * chaque enrichissement. Distinct de {@code /api/quality-benchmark} (modèle brut) et de
 * {@code /api/benchmark} (performance pure).</p>
 *
 * <p><b>Avertissement :</b> endpoint bloquant (plusieurs appels LLM par question × nombre de bras),
 * lent sur CPU.</p>
 */
@RestController
@RequestMapping("/api/ablation")
@Tag(name = "Ablation", description = "Gain marginal des enrichissements (RAG, fine-tuning)")
public class RagAblationController {

    private final RagAblationService service;

    public RagAblationController(RagAblationService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(
            summary = "Lancer un passage d'ablation A/B",
            description = "Évalue une matrice de configurations (bras) sur le benchmark tenu à l'écart : "
                    + "qualité de génération (exactitude, hallucination), qualité de retrieval "
                    + "(Hit@k / MRR / Recall@k) et latence. Corps vide = matrice par défaut "
                    + "(LLM seul vs RAG sur le modèle actif). Chaque bras peut fixer un 'model' "
                    + "(comparaison base vs fine-tuné) et 'useRag'. Bloquant, lent sur CPU.")
    public AblationReport run(@RequestBody(required = false) AblationRequest request) {
        return service.run(request);
    }

    @PostMapping("/async")
    @Operation(
            summary = "Lancer un passage d'ablation asynchrone (suivi, annulable)",
            description = "Version non bloquante de POST /api/ablation : renvoie un jobId à suivre "
                    + "via GET /api/ablation/jobs/{jobId} (progression réelle question par question, "
                    + "rapport persisté côté serveur). Une seule ablation à la fois (409 sinon). "
                    + "Annulable via DELETE /api/ablation/jobs/{jobId}.")
    public ResponseEntity<Map<String, String>> runAsync(
            @RequestBody(required = false) AblationRequest request) {
        String jobId = service.submit(request);
        if (jobId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Une ablation est déjà en cours"));
        }
        return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "PENDING"));
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "Suivi d'un passage d'ablation asynchrone (progression + rapport final)")
    public AblationJob getJob(@PathVariable String jobId) {
        AblationJob job = service.getJob(jobId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ablation inconnue: " + jobId);
        }
        return job;
    }

    @GetMapping("/jobs")
    @Operation(summary = "Historique des passages d'ablation (les plus récents d'abord)")
    public List<AblationJob> listJobs() {
        return service.getJobs();
    }

    @DeleteMapping("/jobs/{jobId}")
    @Operation(
            summary = "Annuler un passage d'ablation en cours",
            description = "Annulation coopérative (même convention que DELETE /api/dataset/generate/"
                    + "{taskId}) : prise en compte entre deux questions du benchmark. "
                    + "409 si le job est déjà terminé.")
    public ResponseEntity<Map<String, String>> cancelJob(@PathVariable String jobId) {
        if (service.getJob(jobId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ablation inconnue: " + jobId);
        }
        if (!service.requestCancel(jobId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Le job est déjà terminé"));
        }
        return ResponseEntity.ok(Map.of("jobId", jobId, "status", "CANCELLING"));
    }
}
