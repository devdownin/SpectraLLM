package fr.spectra.controller;

import fr.spectra.dto.QualityBenchmarkReport;
import fr.spectra.dto.QualityCompareJob;
import fr.spectra.service.BenchmarkDraftService;
import fr.spectra.service.QualityBenchmarkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
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
    private final BenchmarkDraftService draftService;

    public QualityBenchmarkController(QualityBenchmarkService service, BenchmarkDraftService draftService) {
        this.service = service;
        this.draftService = draftService;
    }

    @GetMapping("/benchmark")
    @Operation(
            summary = "Benchmark en vigueur",
            description = "Origine (CONFIGURED : spectra.benchmark.quality-file, CURATED : benchmark relu du corpus, "
                    + "BUNDLED : benchmark embarqué, étranger à votre corpus) et composition : questions avec "
                    + "réponse, hors corpus, et celles qui annotent expectedSources (nécessaire aux métriques de retrieval)."
    )
    public QualityBenchmarkService.BenchmarkInfo benchmark() {
        return service.benchmarkInfo();
    }

    @PutMapping("/benchmark")
    @Operation(
            summary = "Enregistrer le benchmark relu",
            description = "Remplace le benchmark relu du corpus (corpus.jsonl dans spectra.benchmark.draft-dir) ; il devient "
                    + "celui de l'évaluation, de l'ablation et de la calibration. 400 si une entrée est invalide, "
                    + "409 si spectra.benchmark.quality-file est défini (il prime)."
    )
    public QualityBenchmarkService.BenchmarkInfo saveBenchmark(@RequestBody BenchmarkUpload upload) {
        try {
            return service.saveCurated(upload.items());
        } catch (IOException e) {
            throw new UncheckedIOException("Écriture du benchmark impossible", e);
        }
    }

    /** Corps de {@code PUT /benchmark}. */
    public record BenchmarkUpload(List<QualityBenchmarkService.BenchmarkEntry> items) {}

    @PostMapping("/draft/async")
    @Operation(
            summary = "Générer un brouillon de benchmark en tâche de fond (suivi, annulable)",
            description = "Version non bloquante de POST /draft : renvoie un jobId à suivre via "
                    + "GET /draft/jobs/{jobId} (les questions apparaissent au fil de la génération). Un seul "
                    + "brouillon à la fois (409 sinon)."
    )
    public ResponseEntity<Map<String, String>> draftAsync(@RequestParam(required = false) String collection,
                                                          @RequestParam(defaultValue = "30") int size) {
        String jobId = draftService.submit(collection, size);
        if (jobId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Un brouillon est déjà en cours"));
        }
        return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "PENDING"));
    }

    @GetMapping("/draft/jobs/{jobId}")
    @Operation(summary = "Suivi d'un brouillon de benchmark (progression + questions produites)")
    public BenchmarkDraftService.DraftJob draftJob(@PathVariable String jobId) {
        BenchmarkDraftService.DraftJob job = draftService.getJob(jobId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Brouillon inconnu: " + jobId);
        }
        return job;
    }

    @DeleteMapping("/draft/jobs/{jobId}")
    @Operation(summary = "Annuler un brouillon en cours (les questions déjà produites sont conservées)")
    public ResponseEntity<Map<String, String>> cancelDraft(@PathVariable String jobId) {
        if (draftService.getJob(jobId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Brouillon inconnu: " + jobId);
        }
        if (!draftService.cancel(jobId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Le brouillon est déjà terminé"));
        }
        return ResponseEntity.accepted().body(Map.of("status", "CANCEL_REQUESTED"));
    }

    @PostMapping("/draft")
    @Operation(
            summary = "Générer un brouillon de benchmark depuis le corpus",
            description = "Tire un passage par document (à tour de rôle) et en fait une question + réponse attendue "
                    + "avec le LLM, écrites en JSONL dans spectra.benchmark.draft-dir. À relire avant usage : "
                    + "reformuler les questions trop proches du texte, ajouter 10 à 20 % de questions hors corpus "
                    + "(answerable=false). Bloquant : un appel LLM par question (size ≤ 60)."
    )
    public BenchmarkDraftService.DraftResult draft(@RequestParam(required = false) String collection,
                                                   @RequestParam(defaultValue = "30") int size) {
        try {
            return draftService.draft(collection, size);
        } catch (IOException e) {
            throw new UncheckedIOException("Écriture du brouillon de benchmark impossible", e);
        }
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
}
