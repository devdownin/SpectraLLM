package fr.spectra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.spectra.dto.AblationArmReport;
import fr.spectra.dto.AblationJob;
import fr.spectra.dto.AblationReport;
import fr.spectra.dto.AblationRequest;
import fr.spectra.dto.QualityBenchmarkItem;
import fr.spectra.dto.QualityBenchmarkReport;
import fr.spectra.dto.QueryRequest;
import fr.spectra.dto.QueryResponse;
import fr.spectra.dto.RetrievalMetrics;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Harnais d'<b>ablation A/B</b> pour quantifier le gain marginal de chaque enrichissement
 * (RAG, fine-tuning) sur le benchmark tenu à l'écart.
 *
 * <p>Contrairement à {@link QualityBenchmarkService} qui interroge le modèle <b>brut</b>, chaque
 * question est ici passée dans le pipeline complet {@link RagService}. On mesure ainsi trois
 * familles de métriques sur la <b>même</b> base de questions :</p>
 * <ol>
 *   <li><b>Génération</b> — exactitude (LLM-juge 1-10) et taux d'hallucination, via
 *       {@link QualityBenchmarkService#judgeAnswer}.</li>
 *   <li><b>Retrieval</b> — Hit@k / MRR / Recall@k, calculés de façon déterministe à partir des
 *       sources renvoyées et du champ {@code expectedSources} du benchmark.</li>
 *   <li><b>Coût</b> — latence moyenne et médiane (p50).</li>
 * </ol>
 *
 * <p>Deux axes typiques :</p>
 * <ul>
 *   <li><b>Gain du RAG</b> — un bras {@code useRag=false} vs un bras {@code useRag=true} (même modèle).</li>
 *   <li><b>Gain du fine-tuning</b> — un bras modèle de base vs un bras modèle fine-tuné (RAG actif).</li>
 * </ul>
 *
 * <p>Les requêtes sont émises à température 0 pour la reproductibilité des deltas. Passage
 * <b>bloquant</b> (plusieurs appels LLM par question × nombre de bras), lent sur CPU.</p>
 */
@Service
public class RagAblationService {

    private static final Logger log = LoggerFactory.getLogger(RagAblationService.class);

    private final RagService ragService;
    private final QualityBenchmarkService qualityBenchmarkService;
    private final LlmChatClient chatClient;
    private final ModelSwitchCoordinator modelSwitch;
    private final Path workDir;

    /** Jobs d'ablation suivis (en mémoire + persistés en JSON), comme les comparaisons qualité. */
    private final Map<String, AblationJob> jobs = new ConcurrentHashMap<>();
    /** Une seule ablation asynchrone à la fois (409 sinon) — le passage bascule le modèle actif. */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Annulation coopérative : jobIds dont l'arrêt a été demandé (vérifié entre chaque question). */
    private final Set<String> cancelRequested = ConcurrentHashMap.newKeySet();
    private final Object persistLock = new Object();
    private final ObjectMapper jobMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private Path jobsFile;

    // Proxy Spring pour que @Async prenne effet sur l'appel interne (fallback `this` hors Spring).
    @Autowired @Lazy
    private RagAblationService self;

    public RagAblationService(RagService ragService,
                              QualityBenchmarkService qualityBenchmarkService,
                              LlmChatClient chatClient,
                              ModelSwitchCoordinator modelSwitch,
                              @Value("${spectra.fine-tuning.work-dir:./data/fine-tuning}") String workDir) {
        this.ragService = ragService;
        this.qualityBenchmarkService = qualityBenchmarkService;
        this.chatClient = chatClient;
        this.modelSwitch = modelSwitch;
        this.workDir = Path.of(workDir);
    }

    /**
     * Restaure les jobs persistés et réconcilie ceux restés non-terminaux (orphelins d'une
     * ancienne JVM) en FAILED — même logique que les comparaisons qualité.
     */
    @PostConstruct
    void init() {
        jobsFile = workDir.resolve("ablation-jobs.json");
        if (Files.exists(jobsFile)) {
            try {
                var type = jobMapper.getTypeFactory()
                        .constructMapType(Map.class, String.class, AblationJob.class);
                Map<String, AblationJob> loaded = jobMapper.readValue(jobsFile.toFile(), type);
                jobs.putAll(loaded);
                log.info("Ablations restaurées: {} jobs", loaded.size());
            } catch (Exception e) {
                log.warn("Impossible de charger les ablations persistées: {}", e.getMessage());
            }
        }
        boolean reconciled = false;
        for (AblationJob j : jobs.values()) {
            if (j.status() == AblationJob.Status.PENDING || j.status() == AblationJob.Status.RUNNING) {
                jobs.put(j.jobId(), j.failed("Interrompu par un redémarrage du serveur"));
                reconciled = true;
            }
        }
        if (reconciled) persistJobs();
    }

    private void persistJobs() {
        if (jobsFile == null) return;
        synchronized (persistLock) {
            try {
                Files.createDirectories(workDir);
                Path tmp = jobsFile.resolveSibling(jobsFile.getFileName() + ".tmp");
                jobMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), jobs);
                try {
                    Files.move(tmp, jobsFile,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, jobsFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                log.warn("Échec persistance des ablations: {}", e.getMessage());
            }
        }
    }

    private void updateJob(String jobId, java.util.function.UnaryOperator<AblationJob> updater) {
        AblationJob current = jobs.get(jobId);
        if (current == null) return;
        jobs.put(jobId, updater.apply(current));
        persistJobs();
    }

    /**
     * Lance un passage d'ablation asynchrone (suivi + annulable). Un seul passage à la fois
     * (bascule du modèle actif) : renvoie {@code null} si une ablation tourne déjà (409 côté API).
     */
    public String submit(AblationRequest request) {
        List<JsonNode> entries = qualityBenchmarkService.loadBenchmark();
        if (entries.isEmpty()) {
            throw new IllegalStateException(
                    "Benchmark vide ou introuvable. Fournissez un fichier JSONL "
                            + "(spectra.benchmark.quality-file) ou complétez benchmarks/highway_benchmark.jsonl.");
        }
        if (!running.compareAndSet(false, true)) {
            return null;
        }
        try {
            int armCount = (request != null && request.arms() != null && !request.arms().isEmpty())
                    ? request.arms().size() : defaultArms().size();
            int runs = (request != null && request.runs() != null)
                    ? Math.max(1, Math.min(10, request.runs())) : 1;
            String jobId = UUID.randomUUID().toString();
            String label = armCount + " bras × " + runs + " run(s) · " + entries.size() + " questions";
            // Une unité = une question générée OU notée : bras × runs × questions × 2 phases.
            int totalUnits = armCount * runs * entries.size() * 2;
            jobs.put(jobId, AblationJob.pending(jobId, label, totalUnits));
            persistJobs();
            (self != null ? self : this).runJobAsync(jobId, request);
            return jobId;
        } catch (RuntimeException ex) {
            running.set(false);
            throw ex;
        }
    }

    /** Exécute le passage en tâche de fond, avec progression, annulation et rapport persisté. */
    @Async
    protected void runJobAsync(String jobId, AblationRequest request) {
        try {
            updateJob(jobId, j -> j.progress(0, "Démarrage du passage…"));
            AblationReport report = run(request, new Monitor(jobId));
            updateJob(jobId, j -> j.completed(report));
            log.info("Ablation {} terminée ({} bras)", jobId, report.arms().size());
        } catch (CancellationException e) {
            updateJob(jobId, AblationJob::cancelled);
            log.info("Ablation {} annulée à la demande de l'utilisateur", jobId);
        } catch (Exception e) {
            log.error("Ablation {} échouée: {}", jobId, e.getMessage());
            updateJob(jobId, j -> j.failed(e.getMessage() != null ? e.getMessage() : e.toString()));
        } finally {
            cancelRequested.remove(jobId);
            running.set(false);
        }
    }

    /**
     * Demande l'arrêt coopératif d'un job en cours (pris en compte entre deux questions).
     *
     * @return {@code false} si le job est inconnu ou déjà terminal
     */
    public boolean requestCancel(String jobId) {
        AblationJob job = jobs.get(jobId);
        if (job == null || (job.status() != AblationJob.Status.PENDING
                && job.status() != AblationJob.Status.RUNNING)) {
            return false;
        }
        cancelRequested.add(jobId);
        return true;
    }

    public AblationJob getJob(String jobId) {
        return jobs.get(jobId);
    }

    /** Historique des jobs, les plus récents d'abord. */
    public List<AblationJob> getJobs() {
        return jobs.values().stream()
                .sorted(Comparator.comparing(AblationJob::createdAt).reversed())
                .toList();
    }

    /**
     * Progression + annulation coopérative d'un passage. {@code jobId == null} = passage
     * synchrone historique : toutes les méthodes sont des no-ops.
     */
    private final class Monitor {
        private final String jobId;
        private final AtomicInteger processed = new AtomicInteger();

        Monitor(String jobId) { this.jobId = jobId; }

        /** Une unité de travail terminée (question générée ou notée). */
        void tick(String step) {
            if (jobId == null) return;
            int p = processed.incrementAndGet();
            updateJob(jobId, j -> j.progress(p, step));
        }

        /** Change le jalon affiché sans avancer la progression. */
        void step(String step) {
            if (jobId == null) return;
            updateJob(jobId, j -> j.progress(processed.get(), step));
        }

        /** Interrompt le passage si l'annulation a été demandée. */
        void checkCancelled() {
            if (jobId != null && cancelRequested.contains(jobId)) {
                throw new CancellationException("Ablation annulée");
            }
        }
    }

    /** Exécute tous les bras de la requête sur le benchmark et retourne le rapport comparatif. */
    public AblationReport run(AblationRequest request) {
        return run(request, new Monitor(null));
    }

    private AblationReport run(AblationRequest request, Monitor monitor) {
        Instant started = Instant.now();
        List<JsonNode> entries = qualityBenchmarkService.loadBenchmark();
        if (entries.isEmpty()) {
            throw new IllegalStateException(
                    "Benchmark vide ou introuvable. Fournissez un fichier JSONL "
                            + "(spectra.benchmark.quality-file) ou complétez benchmarks/highway_benchmark.jsonl.");
        }

        List<AblationRequest.Arm> arms =
                (request != null && request.arms() != null && !request.arms().isEmpty())
                        ? request.arms()
                        : defaultArms();
        int maxChunks = (request != null && request.maxContextChunks() != null)
                ? request.maxContextChunks() : 5;
        int runs = (request != null && request.runs() != null)
                ? Math.max(1, Math.min(10, request.runs())) : 1;

        // Verrou global tenu sur TOUS les bras : les deltas d'une ablation ne sont comparables
        // que si aucun autre harnais (évaluation, benchmark qualité) ne bascule le modèle actif
        // au milieu du passage.
        modelSwitch.lock();
        try {
            List<AblationArmReport> armReports = new ArrayList<>();
            for (int a = 0; a < arms.size(); a++) {
                monitor.checkCancelled();
                monitor.step("Bras " + (a + 1) + "/" + arms.size() + " · « " + arms.get(a).label() + " »…");
                armReports.add(runArm(arms.get(a), entries, maxChunks, runs, started, monitor));
            }
            return new AblationReport(armReports, entries.size(), started, Instant.now());
        } finally {
            modelSwitch.unlock();
        }
    }

    /** Matrice par défaut : LLM seul vs RAG, sur le modèle actif. */
    private List<AblationRequest.Arm> defaultArms() {
        return List.of(
                new AblationRequest.Arm("llm-seul (sans RAG)", null, false, null),
                new AblationRequest.Arm("rag", null, true, null));
    }

    /** Résultat d'une exécution unique du benchmark pour un bras. */
    private record RunResult(
            QualityBenchmarkReport quality,
            RetrievalMetrics retrieval,
            double avgLatency,
            double p50,
            double avgContextTokens,
            Map<String, Integer> applied) {}

    private AblationArmReport runArm(AblationRequest.Arm arm, List<JsonNode> entries,
                                     int maxChunks, int runs, Instant started, Monitor monitor) {
        String previous = chatClient.getActiveModel();
        String evaluatedModel = (arm.model() != null && !arm.model().isBlank()) ? arm.model() : previous;
        if (evaluatedModel != null && !evaluatedModel.equals(previous)) {
            log.info("Ablation '{}' : bascule temporaire du modèle {} → {}",
                    arm.label(), previous, evaluatedModel);
        }
        // La bascule effective (avec attente que le serveur serve le modèle) est faite par
        // runOnce, qui doit de toute façon revenir au modèle évalué après chaque phase de
        // notation par un juge neutre.

        try {
            List<RunResult> rr = new ArrayList<>();
            for (int i = 0; i < runs; i++) {
                monitor.checkCancelled();
                rr.add(runOnce(arm, entries, maxChunks, evaluatedModel, started, monitor));
            }

            // Moyennes (et écarts-types) sur les répétitions.
            List<Double> avgScores  = map(rr, r -> r.quality().avgScore());
            List<Double> hallucs    = map(rr, r -> r.quality().hallucinationRate());
            List<Double> refusals   = map(rr, r -> r.quality().refusalAccuracy());
            List<Double> hits       = map(rr, r -> r.retrieval().hitRate());
            List<Double> mrrs       = map(rr, r -> r.retrieval().mrr());
            List<Double> recalls    = map(rr, r -> r.retrieval().recallAtK());
            List<Double> p50s       = map(rr, RunResult::p50);
            List<Double> tokens     = map(rr, RunResult::avgContextTokens);
            double avgLatency       = mean(map(rr, RunResult::avgLatency));

            RunResult first = rr.getFirst();
            QualityBenchmarkReport q0 = first.quality();
            QualityBenchmarkReport quality = new QualityBenchmarkReport(
                    evaluatedModel, q0.judgeModel(), q0.total(), q0.answerableCount(), q0.unanswerableCount(),
                    mean(avgScores), mean(hallucs), mean(refusals),
                    meanScoresByCategory(rr), q0.items(), started, Instant.now());
            RetrievalMetrics retrieval = new RetrievalMetrics(
                    first.retrieval().evaluatedQuestions(), maxChunks,
                    mean(hits), mean(mrrs), mean(recalls));

            Map<String, Double> stdDev = new LinkedHashMap<>();
            stdDev.put("avgScore", std(avgScores));
            stdDev.put("hallucinationRate", std(hallucs));
            stdDev.put("refusalAccuracy", std(refusals));
            stdDev.put("hitRate", std(hits));
            stdDev.put("mrr", std(mrrs));
            stdDev.put("recallAtK", std(recalls));
            stdDev.put("p50LatencyMs", std(p50s));
            stdDev.put("avgContextTokens", std(tokens));

            log.info("Ablation '{}' (modèle={}, rag={}, runs={}) : score {}±{}/10, halluc {}%, hit@{}={}, "
                    + "tokens≈{}, latence p50={}ms",
                    arm.label(), evaluatedModel, arm.useRag(), runs,
                    String.format(Locale.ROOT, "%.2f", quality.avgScore()),
                    String.format(Locale.ROOT, "%.2f", std(avgScores)),
                    String.format(Locale.ROOT, "%.0f", quality.hallucinationRate() * 100),
                    maxChunks, String.format(Locale.ROOT, "%.2f", retrieval.hitRate()),
                    String.format(Locale.ROOT, "%.0f", mean(tokens)),
                    String.format(Locale.ROOT, "%.0f", mean(p50s)));

            return new AblationArmReport(arm.label(), evaluatedModel, arm.useRag(), arm.overrides(),
                    quality, retrieval, avgLatency, mean(p50s), mean(tokens), runs,
                    stdDev, first.applied());
        } finally {
            // Best-effort : un échec de restauration ne doit pas détruire le rapport calculé.
            modelSwitch.restore(previous);
        }
    }

    /** Réponse en attente de notation (produite en phase 1, jugée en phase 2). */
    private record PendingJudgment(int index, String question, String category,
                                   boolean answerable, String reference, String answer) {}

    /**
     * Une passe du benchmark en deux phases : (1) toutes les questions passent dans le pipeline
     * avec le modèle évalué (latence, retrieval, coût mesurés ici) ; (2) les réponses sont
     * notées — par le juge neutre ({@code spectra.evaluation.judge-model}) s'il est configuré
     * et distinct, sinon par le modèle évalué lui-même.
     */
    private RunResult runOnce(AblationRequest.Arm arm, List<JsonNode> entries,
                              int maxChunks, String evaluatedModel, Instant started, Monitor monitor) {
        String judge = qualityBenchmarkService.resolveJudge(evaluatedModel);
        // La phase de notation de la répétition précédente a pu laisser le juge neutre servi :
        // s'assurer que le modèle évalué est bien celui qui répond (attente de convergence).
        if (evaluatedModel != null && !evaluatedModel.isBlank()
                && !evaluatedModel.equals(chatClient.getActiveModel())) {
            modelSwitch.activate(evaluatedModel);
        }

        List<QualityBenchmarkItem> items = new ArrayList<>();
        List<PendingJudgment> pending = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        int retrievalEvaluated = 0;
        double hitSum = 0, rrSum = 0, recallSum = 0;
        long tokenSum = 0;
        int answered = 0;
        Map<String, Integer> applied = new LinkedHashMap<>();

        // ── Phase 1 : génération via le pipeline (modèle évalué) ──
        int generated = 0;
        for (JsonNode entry : entries) {
            monitor.checkCancelled();
            generated++;
            monitor.tick("« " + arm.label() + " » · génération " + generated + "/" + entries.size());
            String question   = entry.path("question").asText("");
            String category   = entry.path("category").asText("inconnu");
            boolean answerable = entry.path("answerable").asBoolean(true);
            String reference  = entry.hasNonNull("reference") ? entry.get("reference").asText() : null;
            List<String> expected = readExpectedSources(entry);

            QueryResponse resp;
            try {
                // Température 0 / top-p 1 : génération déterministe pour des deltas comparables.
                resp = ragService.query(new QueryRequest(
                        question, maxChunks, null, null, 0.0f, 1.0f, null, arm.useRag()),
                        arm.overrides());
            } catch (Exception e) {
                log.warn("Ablation '{}' : échec requête « {} » : {}", arm.label(), question, e.getMessage());
                items.add(new QualityBenchmarkItem(question, category, answerable, reference,
                        null, null, null, null, "Échec requête RAG: " + e.getMessage()));
                continue;
            }

            latencies.add(resp.durationMs());
            tokenSum += estimateContextTokens(resp.sources());
            answered++;
            tallyApplied(applied, resp);
            items.add(null); // réservé — rempli en phase 2 pour préserver l'ordre des entrées
            pending.add(new PendingJudgment(items.size() - 1,
                    question, category, answerable, reference, resp.answer()));

            if (arm.useRag() && !expected.isEmpty()) {
                RetrievalHit hit = scoreRetrieval(resp.sources(), expected, maxChunks);
                retrievalEvaluated++;
                hitSum    += hit.hit() ? 1.0 : 0.0;
                rrSum     += hit.reciprocalRank();
                recallSum += hit.recall();
            }
        }

        // ── Phase 2 : notation (une seule bascule vers le juge neutre s'il y a lieu) ──
        if (!pending.isEmpty()) {
            if (judge != null && !judge.isBlank() && !judge.equals(chatClient.getActiveModel())) {
                log.info("Ablation '{}' : notation par le juge neutre '{}'", arm.label(), judge);
                modelSwitch.activate(judge);
            }
            int judged = 0;
            for (PendingJudgment p : pending) {
                monitor.checkCancelled();
                judged++;
                monitor.tick("« " + arm.label() + " » · notation " + judged + "/" + pending.size());
                items.set(p.index(), qualityBenchmarkService.judgeAnswer(
                        p.question(), p.category(), p.answerable(), p.reference(), p.answer()));
            }
        }

        QualityBenchmarkReport quality =
                qualityBenchmarkService.aggregate(evaluatedModel, judge, items, started);
        RetrievalMetrics retrieval = new RetrievalMetrics(
                retrievalEvaluated, maxChunks,
                retrievalEvaluated > 0 ? hitSum / retrievalEvaluated : 0.0,
                retrievalEvaluated > 0 ? rrSum / retrievalEvaluated : 0.0,
                retrievalEvaluated > 0 ? recallSum / retrievalEvaluated : 0.0);

        double avgLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double p50 = percentile(latencies, 50);
        double avgTokens = answered > 0 ? (double) tokenSum / answered : 0.0;

        return new RunResult(quality, retrieval, avgLatency, p50, avgTokens, applied);
    }

    /**
     * Estime le nombre de tokens du contexte injecté (somme des chunks sources).
     * Approximation usuelle ~4 caractères par token — déterministe, contrairement à la latence.
     */
    private long estimateContextTokens(List<QueryResponse.Source> sources) {
        if (sources == null) return 0;
        long chars = 0;
        for (QueryResponse.Source s : sources) {
            if (s.text() != null) chars += s.text().length();
        }
        return Math.round(chars / 4.0);
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    private static List<Double> map(List<RunResult> rr, java.util.function.ToDoubleFunction<RunResult> f) {
        List<Double> out = new ArrayList<>(rr.size());
        for (RunResult r : rr) out.add(f.applyAsDouble(r));
        return out;
    }

    static double mean(List<Double> xs) {
        return xs.isEmpty() ? 0.0 : xs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /** Écart-type d'échantillon (N-1) ; 0 si moins de 2 répétitions. */
    static double std(List<Double> xs) {
        int n = xs.size();
        if (n < 2) return 0.0;
        double m = mean(xs);
        double s = xs.stream().mapToDouble(d -> (d - m) * (d - m)).sum();
        return Math.sqrt(s / (n - 1));
    }

    /** Moyenne des scores par catégorie sur les répétitions. */
    private static Map<String, Double> meanScoresByCategory(List<RunResult> rr) {
        Map<String, double[]> agg = new java.util.TreeMap<>();   // cat → [somme, n]
        for (RunResult r : rr) {
            r.quality().scoresByCategory().forEach((cat, v) -> {
                double[] a = agg.computeIfAbsent(cat, k -> new double[2]);
                a[0] += v; a[1] += 1;
            });
        }
        Map<String, Double> out = new java.util.TreeMap<>();
        agg.forEach((cat, a) -> out.put(cat, a[1] > 0 ? a[0] / a[1] : 0.0));
        return out;
    }

    /** Incrémente les compteurs des modules effectivement déclenchés par cette réponse. */
    private void tallyApplied(Map<String, Integer> applied, QueryResponse resp) {
        if (resp.rerankApplied())         applied.merge("rerank", 1, Integer::sum);
        if (resp.hybridSearchApplied())   applied.merge("hybrid", 1, Integer::sum);
        if (resp.multiQueryApplied())     applied.merge("multiQuery", 1, Integer::sum);
        if (resp.correctiveApplied())     applied.merge("corrective", 1, Integer::sum);
        if (resp.compressionApplied())    applied.merge("compression", 1, Integer::sum);
        if (resp.selfRagApplied())        applied.merge("selfRag", 1, Integer::sum);
        if (resp.conversationalApplied()) applied.merge("conversational", 1, Integer::sum);
        if (resp.agenticApplied())        applied.merge("agentic", 1, Integer::sum);
        if (resp.semanticDedupApplied())  applied.merge("semanticDedup", 1, Integer::sum);
        if (resp.longContextApplied())    applied.merge("longContext", 1, Integer::sum);
    }

    /** Issue du scoring de retrieval pour une question annotée. */
    record RetrievalHit(boolean hit, double reciprocalRank, double recall) {}

    /**
     * Calcule Hit@k, le reciprocal rank et le recall@k pour une question annotée.
     * Une source est « pertinente » si son {@code sourceFile} contient (insensible à la casse)
     * l'un des libellés attendus — robuste aux différences de chemin/extension.
     */
    RetrievalHit scoreRetrieval(List<QueryResponse.Source> sources, List<String> expected, int k) {
        if (sources == null || sources.isEmpty() || expected.isEmpty()) {
            return new RetrievalHit(false, 0.0, 0.0);
        }
        int limit = Math.min(k, sources.size());
        int firstRank = 0;
        Set<String> matched = new HashSet<>();
        for (int i = 0; i < limit; i++) {
            String file = sources.get(i).sourceFile();
            if (file == null) continue;
            String lf = file.toLowerCase(Locale.ROOT);
            for (String exp : expected) {
                if (lf.contains(exp.toLowerCase(Locale.ROOT))) {
                    if (firstRank == 0) firstRank = i + 1;
                    matched.add(exp.toLowerCase(Locale.ROOT));
                }
            }
        }
        boolean hit = firstRank > 0;
        double rr = hit ? 1.0 / firstRank : 0.0;
        double recall = (double) matched.size() / expected.size();
        return new RetrievalHit(hit, rr, recall);
    }

    private List<String> readExpectedSources(JsonNode entry) {
        JsonNode node = entry.get("expectedSources");
        if (node == null || !node.isArray()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) {
            String s = n.asText("").strip();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** Percentile (méthode du plus proche rang, base 1) sur une copie triée. */
    double percentile(List<Long> values, int pct) {
        if (values.isEmpty()) return 0.0;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return sorted.get(idx);
    }
}
