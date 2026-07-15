package fr.spectra.service;

import fr.spectra.service.dataset.DatasetGeneratorService;
import fr.spectra.service.dataset.DpoGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Vue agrégée de TOUTES les tâches asynchrones du backend, diffusée en SSE.
 *
 * <p>Chaque famille de tâches (ingestion, génération de dataset, DPO, fine-tuning,
 * évaluations, comparaisons A/B, installations de modèles, benchmarks qualité) expose
 * déjà son endpoint REST ; l'UI devait donc les poller un par un pour afficher l'activité
 * globale. Ce service assemble un instantané compact unique et le pousse sur
 * {@code /api/sse/tasks} : une seule connexion remplace les boucles de polling, avec une
 * latence de mise à jour de {@value #POLL_SECONDS} s.</p>
 *
 * <p><b>Compact :</b> seuls les champs utiles au suivi (id, statut, libellés, compteurs de
 * progression, horodatages, erreur) sont émis — pas les rapports détaillés (scores,
 * items A/B…) qui peuvent peser des centaines de Ko. Les noms de champs reprennent
 * exactement ceux des endpoints REST correspondants, pour que le client normalise les
 * deux sources avec le même code.</p>
 *
 * <p><b>Diffusion :</b> un seul assemblage périodique partagé entre tous les clients
 * ({@code share()} : démarre au premier abonné, s'arrête au dernier) ;
 * {@code distinctUntilChanged()} n'émet que lorsque quelque chose a réellement changé.
 * Chaque source est interrogée en best-effort : une famille en erreur est simplement
 * absente de l'instantané, les autres restent visibles.</p>
 */
@Service
public class TaskActivityService {

    private static final Logger log = LoggerFactory.getLogger(TaskActivityService.class);
    private static final int POLL_SECONDS = 2;

    private final IngestionService ingestionService;
    private final DatasetGeneratorService datasetGenerator;
    private final DpoGenerationService dpoService;
    private final FineTuningService fineTuningService;
    private final EvaluationService evaluationService;
    private final QualityBenchmarkService qualityBenchmarkService;
    private final RagAblationService ragAblationService;
    private final LlmFitService llmFitService;

    private final Flux<Map<String, Object>> shared;

    public TaskActivityService(IngestionService ingestionService,
                               DatasetGeneratorService datasetGenerator,
                               DpoGenerationService dpoService,
                               FineTuningService fineTuningService,
                               EvaluationService evaluationService,
                               QualityBenchmarkService qualityBenchmarkService,
                               RagAblationService ragAblationService,
                               LlmFitService llmFitService) {
        this.ingestionService = ingestionService;
        this.datasetGenerator = datasetGenerator;
        this.dpoService = dpoService;
        this.fineTuningService = fineTuningService;
        this.evaluationService = evaluationService;
        this.qualityBenchmarkService = qualityBenchmarkService;
        this.ragAblationService = ragAblationService;
        this.llmFitService = llmFitService;
        this.shared = Flux.interval(Duration.ofSeconds(POLL_SECONDS))
                .onBackpressureDrop()
                .map(i -> snapshot())
                .distinctUntilChanged()
                .share();
    }

    /** Flux partagé des instantanés (émis uniquement quand l'état change). */
    public Flux<Map<String, Object>> stream() {
        return shared;
    }

    /** Instantané compact de toutes les familles de tâches. */
    public Map<String, Object> snapshot() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("ingest", safe("ingest", () -> ingestionService.getAllTasks().stream().map(t -> compact(
                "taskId", t.taskId(), "status", name(t.status()), "files", t.files(),
                "chunksCreated", t.chunksCreated(), "chunksExpected", t.chunksExpected(),
                "error", t.error(), "createdAt", t.createdAt(), "completedAt", t.completedAt())).toList()));
        snap.put("dataset", safe("dataset", () -> datasetGenerator.getAllTasks().stream().map(t -> compact(
                "taskId", t.taskId(), "status", name(t.status()), "pairsGenerated", t.pairsGenerated(),
                "chunksProcessed", t.chunksProcessed(), "totalChunks", t.totalChunks(),
                "error", t.error(), "createdAt", t.createdAt())).toList()));
        snap.put("dpo", safe("dpo", () -> dpoService.getAllTasks().stream().map(t -> compact(
                "taskId", t.taskId(), "status", t.status(), "processed", t.processed(), "total", t.total(),
                "error", t.error(), "startedAt", t.startedAt(), "completedAt", t.completedAt())).toList()));
        snap.put("training", safe("training", () -> fineTuningService.getAllJobs().stream().map(j -> compact(
                "jobId", j.jobId(), "status", name(j.status()), "modelName", j.modelName(),
                "currentEpoch", j.currentEpoch(), "totalEpochs", j.totalEpochs(), "currentStep", j.currentStep(),
                "error", j.error(), "createdAt", j.createdAt(), "completedAt", j.completedAt())).toList()));
        snap.put("evaluations", safe("evaluations", () -> evaluationService.getAllReports().stream().map(e -> compact(
                "evalId", e.evalId(), "status", e.status(), "modelName", e.modelName(),
                "processed", e.processed(), "testSetSize", e.testSetSize(),
                "error", e.error(), "startedAt", e.startedAt(), "completedAt", e.completedAt())).toList()));
        snap.put("ab", safe("ab", () -> evaluationService.getAllAbReports().stream().map(ab -> compact(
                "abId", ab.abId(), "status", ab.status(), "modelA", ab.modelA(), "modelB", ab.modelB(),
                "processed", ab.processed(), "testSetSize", ab.testSetSize(),
                "error", ab.error(), "startedAt", ab.startedAt(), "completedAt", ab.completedAt())).toList()));
        snap.put("installs", safe("installs", () -> llmFitService.getInstallations().stream().map(j -> compact(
                "jobId", j.jobId(), "status", name(j.status()), "modelName", j.modelName(),
                "progress", j.progress(), "currentStep", j.currentStep(),
                "error", j.error(), "createdAt", j.createdAt(), "completedAt", j.completedAt())).toList()));
        snap.put("benchmarks", safe("benchmarks", () -> qualityBenchmarkService.getCompareJobs().stream().map(j -> compact(
                "jobId", j.jobId(), "status", name(j.status()), "baseline", j.baseline(),
                "candidate", j.candidate(), "currentStep", j.currentStep(),
                "error", j.error(), "createdAt", j.createdAt(), "completedAt", j.completedAt())).toList()));
        snap.put("ablations", safe("ablations", () -> ragAblationService.getJobs().stream().map(j -> compact(
                "jobId", j.jobId(), "status", name(j.status()), "label", j.label(),
                "processedUnits", j.processedUnits(), "totalUnits", j.totalUnits(),
                "currentStep", j.currentStep(),
                "error", j.error(), "createdAt", j.createdAt(), "completedAt", j.completedAt())).toList()));
        return snap;
    }

    /** Best-effort : une source en panne devient une liste vide, les autres restent visibles. */
    private List<Map<String, Object>> safe(String source, Supplier<List<Map<String, Object>>> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.debug("Instantané des tâches : source '{}' indisponible ({})", source, e.getMessage());
            return List.of();
        }
    }

    /** Map clé/valeur tolérant les valeurs null (contrairement à {@code Map.of}). */
    private static Map<String, Object> compact(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String name(Enum<?> e) {
        return e != null ? e.name() : null;
    }
}
