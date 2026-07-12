package fr.spectra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.spectra.dto.QualityBenchmarkItem;
import fr.spectra.dto.QualityBenchmarkReport;
import fr.spectra.dto.QualityCompareJob;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Harnais d'évaluation <b>qualité</b> sur un benchmark <b>tenu à l'écart</b> (jamais dans
 * l'entraînement), par opposition à {@link EvaluationService} qui échantillonne le dataset
 * généré (et souffre donc d'une fuite de données vis-à-vis d'un modèle fine-tuné).
 *
 * <p>Deux mesures complémentaires via LLM-juge :
 * <ol>
 *   <li><b>Exactitude</b> (questions answerable) : score 1-10 vs réponse de référence.</li>
 *   <li><b>Hallucination</b> (questions non-answerable, réponse absente du corpus) : le modèle
 *       doit s'abstenir ; on mesure le taux où il invente une réponse.</li>
 * </ol>
 *
 * <p>Le passage est <b>bloquant</b> (le benchmark est petit). Un {@code model} peut être fourni
 * pour évaluer un modèle précis : le modèle actif est alors basculé temporairement (avec attente
 * que le serveur le serve réellement, cf. {@link ModelSwitchCoordinator}) puis restauré, ce qui
 * permet de comparer base vs fine-tuné.</p>
 *
 * <p><b>Juge.</b> Comme dans {@link EvaluationService}, si {@code spectra.evaluation.judge-model}
 * est configuré (et distinct du modèle évalué), la notation se fait en deux phases : génération de
 * toutes les réponses avec le modèle évalué, puis une seule bascule vers le juge neutre pour noter.
 * Sans juge neutre, le modèle évalué se note lui-même — dans une comparaison, chaque modèle serait
 * alors son propre juge, ce qui biaise les scores.</p>
 */
@Service
public class QualityBenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(QualityBenchmarkService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = fr.spectra.model.AssistantPersona.SYSTEM_PROMPT;

    private static final String SCORE_JUDGE_PROMPT = """
            Tu es un évaluateur expert. Compare la réponse évaluée à la réponse de référence :
            - Exactitude factuelle, couverture des points essentiels, clarté.
            Réponds UNIQUEMENT avec ce JSON : {"score": <entier 1-10>, "justification": "<phrase courte>"}
            """;

    private static final String REFUSAL_JUDGE_PROMPT = """
            La question posée porte sur une information qui N'EST PAS disponible dans la documentation.
            Une bonne réponse doit reconnaître l'absence d'information ou refuser de répondre, sans inventer.
            La réponse évaluée s'abstient-elle correctement (refus/aveu d'ignorance) ou invente-t-elle une réponse ?
            Réponds UNIQUEMENT avec ce JSON : {"refused": true|false, "justification": "<phrase courte>"}
            """;

    /** Mapper dédié à la (dé)sérialisation des jobs persistés (gère les {@link Instant}). */
    private static final ObjectMapper jobMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final LlmChatClient chatClient;
    private final ModelSwitchCoordinator modelSwitch;
    /** Modèle-juge neutre (vide = le modèle évalué se juge lui-même, comportement par défaut). */
    private final String judgeModel;
    private final String benchmarkPath;
    private final Path workDir;

    /** Jobs de comparaison suivis (en mémoire + persistés en JSON), à l'image d'EvaluationService. */
    private final Map<String, QualityCompareJob> compareJobs = new ConcurrentHashMap<>();
    /**
     * Une seule comparaison asynchrone à la fois (409 sinon) — réponse immédiate côté API.
     * La protection d'exécution proprement dite (vis-à-vis des évaluations, ablations et
     * benchmarks lancés par ailleurs) est le verrou global de {@link ModelSwitchCoordinator}.
     */
    private final AtomicBoolean compareRunning = new AtomicBoolean(false);
    private final Object persistLock = new Object();
    private Path compareJobsFile;

    // Proxy Spring pour que @Async prenne effet sur l'appel interne (fallback `this` hors Spring).
    @Autowired @Lazy
    private QualityBenchmarkService self;

    public QualityBenchmarkService(LlmChatClient chatClient,
                                   ModelSwitchCoordinator modelSwitch,
                                   @Value("${spectra.evaluation.judge-model:}") String judgeModel,
                                   @Value("${spectra.benchmark.quality-file:}") String benchmarkPath,
                                   @Value("${spectra.fine-tuning.work-dir:./data/fine-tuning}") String workDir) {
        this.chatClient = chatClient;
        this.modelSwitch = modelSwitch;
        this.judgeModel = judgeModel != null ? judgeModel.trim() : "";
        this.benchmarkPath = benchmarkPath;
        this.workDir = Path.of(workDir);
    }

    /**
     * Restaure les jobs persistés et réconcilie ceux restés non-terminaux (orphelins d'une ancienne
     * JVM) en FAILED — même logique que les autres suivis (fine-tuning, installations).
     */
    @PostConstruct
    void init() {
        compareJobsFile = workDir.resolve("quality-compare-jobs.json");
        if (Files.exists(compareJobsFile)) {
            try {
                var type = jobMapper.getTypeFactory()
                        .constructMapType(Map.class, String.class, QualityCompareJob.class);
                Map<String, QualityCompareJob> loaded = jobMapper.readValue(compareJobsFile.toFile(), type);
                compareJobs.putAll(loaded);
                log.info("Comparaisons qualité restaurées: {} jobs", loaded.size());
            } catch (Exception e) {
                log.warn("Impossible de charger les comparaisons qualité persistées: {}", e.getMessage());
            }
        }
        boolean reconciled = false;
        for (QualityCompareJob j : compareJobs.values()) {
            if (j.status() != QualityCompareJob.Status.COMPLETED
                    && j.status() != QualityCompareJob.Status.FAILED) {
                compareJobs.put(j.jobId(), j.failed("Interrompu par un redémarrage du serveur"));
                log.warn("Comparaison qualité {} ({} vs {}) marquée FAILED : interrompue par un redémarrage",
                        j.jobId(), j.baseline(), j.candidate());
                reconciled = true;
            }
        }
        if (reconciled) persistCompareJobs();
    }

    private void persistCompareJobs() {
        if (compareJobsFile == null) return;
        synchronized (persistLock) {
            try {
                Files.createDirectories(workDir);
                Path tmp = compareJobsFile.resolveSibling(compareJobsFile.getFileName() + ".tmp");
                jobMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), compareJobs);
                try {
                    Files.move(tmp, compareJobsFile,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, compareJobsFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                log.warn("Échec persistance des comparaisons qualité: {}", e.getMessage());
            }
        }
    }

    /**
     * Lance une comparaison qualité asynchrone {@code candidate} vs {@code baseline} sur le benchmark
     * tenu à l'écart. Un seul passage à la fois (bascule du modèle actif) : renvoie {@code null} si une
     * comparaison est déjà en cours (le contrôleur répond alors 409).
     *
     * @return l'identifiant du job, ou {@code null} si une comparaison tourne déjà
     */
    public String submitCompare(String baseline, String candidate) {
        if (baseline == null || baseline.isBlank() || candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("baseline et candidate sont requis.");
        }
        if (!compareRunning.compareAndSet(false, true)) {
            return null;
        }
        try {
            String jobId = UUID.randomUUID().toString();
            compareJobs.put(jobId, QualityCompareJob.pending(jobId, baseline, candidate));
            persistCompareJobs();
            (self != null ? self : this).runCompareAsync(jobId, baseline, candidate);
            return jobId;
        } catch (RuntimeException ex) {
            compareRunning.set(false);
            throw ex;
        }
    }

    /** Exécute la comparaison en tâche de fond : baseline puis candidate, avec suivi de statut. */
    @Async
    protected void runCompareAsync(String jobId, String baseline, String candidate) {
        // Verrou (réentrant) tenu sur les DEUX passages : aucun autre harnais ne doit
        // basculer le modèle actif entre baseline et candidate.
        modelSwitch.lock();
        try {
            // Les jalons du passage (chargement du GGUF, génération, notation) remontent dans
            // currentStep : l'UI montre où en est le job au lieu d'une attente muette.
            java.util.function.Consumer<String> progress =
                    step -> updateCompareJob(jobId, j -> j.running(step));

            updateCompareJob(jobId, j -> j.running("Évaluation du modèle de référence « " + baseline + " »…"));
            QualityBenchmarkReport baselineReport = run(baseline, progress);

            updateCompareJob(jobId, j -> j.withBaselineReport(baselineReport,
                    "Évaluation du modèle candidat « " + candidate + " »…"));
            QualityBenchmarkReport candidateReport = run(candidate, progress);

            updateCompareJob(jobId, j -> j.completed(candidateReport));
            log.info("Comparaison qualité {} terminée : {} ({}/10) vs {} ({}/10)", jobId,
                    baseline, String.format("%.2f", baselineReport.avgScore()),
                    candidate, String.format("%.2f", candidateReport.avgScore()));
        } catch (Exception e) {
            log.error("Comparaison qualité {} échouée: {}", jobId, e.getMessage());
            updateCompareJob(jobId, j -> j.failed(e.getMessage() != null ? e.getMessage() : e.toString()));
        } finally {
            modelSwitch.unlock();
            compareRunning.set(false);
        }
    }

    private void updateCompareJob(String jobId, java.util.function.UnaryOperator<QualityCompareJob> updater) {
        QualityCompareJob current = compareJobs.get(jobId);
        if (current == null) return;
        compareJobs.put(jobId, updater.apply(current));
        persistCompareJobs();
    }

    public QualityCompareJob getCompareJob(String jobId) {
        return compareJobs.get(jobId);
    }

    /** Historique des comparaisons qualité, les plus récentes d'abord. */
    public List<QualityCompareJob> getCompareJobs() {
        return compareJobs.values().stream()
                .sorted(Comparator.comparing(QualityCompareJob::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /**
     * Exécute le benchmark qualité. {@code model} optionnel : si fourni, bascule temporairement
     * le modèle actif (avec attente que le serveur le serve réellement) puis le restaure, pour
     * permettre la comparaison base vs fine-tuné.
     *
     * <p>Deux phases : génération de toutes les réponses avec le modèle évalué, puis notation —
     * par le juge neutre ({@code spectra.evaluation.judge-model}) s'il est configuré et distinct,
     * sinon par le modèle évalué lui-même. Tout le passage se déroule sous le verrou global de
     * bascule (une seule mesure à la fois, tous harnais confondus).</p>
     */
    public QualityBenchmarkReport run(String model) {
        return run(model, step -> { });
    }

    /**
     * Variante suivie de {@link #run(String)} : {@code progress} reçoit les jalons du passage
     * (chargement du modèle, génération, notation) — affichés par l'UI via le
     * {@code currentStep} des jobs de comparaison, pour que l'attente du chargement d'un
     * GGUF (plusieurs minutes possibles) ne ressemble pas à un blocage.
     */
    public QualityBenchmarkReport run(String model, java.util.function.Consumer<String> progress) {
        Instant started = Instant.now();
        List<JsonNode> entries = loadBenchmark();
        if (entries.isEmpty()) {
            throw new IllegalStateException(
                    "Benchmark qualité vide ou introuvable. Fournissez un fichier JSONL "
                            + "(spectra.benchmark.quality-file) ou complétez benchmarks/highway_benchmark.jsonl.");
        }

        modelSwitch.lock();
        try {
            String previous = chatClient.getActiveModel();
            String evaluatedModel = (model != null && !model.isBlank()) ? model : previous;
            try {
                if (evaluatedModel != null && !evaluatedModel.equals(previous)) {
                    log.info("Benchmark qualité : bascule temporaire du modèle actif {} → {}",
                            previous, evaluatedModel);
                    progress.accept("Chargement du modèle « " + evaluatedModel + " »…");
                    modelSwitch.activate(evaluatedModel);
                }

                // ── Phase 1 : génération des réponses avec le modèle évalué ──
                progress.accept("Génération des réponses (« " + evaluatedModel + " », "
                        + entries.size() + " questions)…");
                List<GeneratedEntry> generated = new ArrayList<>(entries.size());
                for (JsonNode entry : entries) {
                    generated.add(generateEntry(entry));
                }

                // ── Phase 2 : notation (une seule bascule vers le juge neutre s'il y a lieu) ──
                String judge = resolveJudge(evaluatedModel);
                if (judge != null && !judge.equals(chatClient.getActiveModel())) {
                    log.info("Benchmark qualité : notation par le juge neutre '{}'", judge);
                    progress.accept("Chargement du juge « " + judge + " »…");
                    modelSwitch.activate(judge);
                }
                progress.accept("Notation des réponses (juge « " + judge + " »)…");
                List<QualityBenchmarkItem> items = new ArrayList<>(generated.size());
                for (GeneratedEntry g : generated) {
                    items.add(judgeEntry(g));
                }

                QualityBenchmarkReport report = aggregate(evaluatedModel, judge, items, started);
                log.info("Benchmark qualité '{}' (juge: {}) : score moyen {}/10, hallucination {} %",
                        evaluatedModel, judge, String.format("%.2f", report.avgScore()),
                        String.format("%.0f", report.hallucinationRate() * 100));
                return report;
            } finally {
                // Best-effort : un échec de restauration ne doit pas détruire le rapport calculé.
                modelSwitch.restore(previous);
            }
        } finally {
            modelSwitch.unlock();
        }
    }

    /** Compare deux modèles sur le même benchmark (base vs fine-tuné). */
    public Map<String, QualityBenchmarkReport> compare(String baseline, String candidate) {
        // Verrou (réentrant) tenu sur TOUTE la comparaison : aucun autre harnais ne doit
        // basculer le modèle actif entre les deux passages.
        modelSwitch.lock();
        try {
            Map<String, QualityBenchmarkReport> out = new LinkedHashMap<>();
            out.put("baseline", run(baseline));
            out.put("candidate", run(candidate));
            return out;
        } finally {
            modelSwitch.unlock();
        }
    }

    /** Modèle qui notera les réponses : juge neutre si configuré et distinct, sinon auto-jugement. */
    String resolveJudge(String evaluatedModel) {
        return (!judgeModel.isEmpty() && !judgeModel.equals(evaluatedModel)) ? judgeModel : evaluatedModel;
    }

    /** Réponse produite (ou erreur rencontrée) par le modèle évalué pour une entrée du benchmark. */
    private record GeneratedEntry(JsonNode entry, String answer, String error) {}

    /** Phase 1 — interroge le modèle évalué (actif) ; l'échec est consigné, pas propagé. */
    private GeneratedEntry generateEntry(JsonNode entry) {
        String question = entry.path("question").asText("");
        try {
            return new GeneratedEntry(entry, chatClient.chat(SYSTEM_PROMPT, question), null);
        } catch (Exception e) {
            log.warn("Échec appel modèle évalué: {}", e.getMessage());
            return new GeneratedEntry(entry, null, "Échec appel modèle: " + e.getMessage());
        }
    }

    /** Phase 2 — note une réponse générée avec le modèle-juge actif. */
    private QualityBenchmarkItem judgeEntry(GeneratedEntry g) {
        JsonNode entry = g.entry();
        String question  = entry.path("question").asText("");
        String category  = entry.path("category").asText("inconnu");
        boolean answerable = entry.path("answerable").asBoolean(true);
        String reference = entry.hasNonNull("reference") ? entry.get("reference").asText() : null;

        if (g.error() != null) {
            return new QualityBenchmarkItem(question, category, answerable, reference,
                    null, null, null, null, g.error());
        }
        return judgeAnswer(question, category, answerable, reference, g.answer());
    }

    /**
     * Juge une réponse <b>déjà produite</b> — par le modèle brut (cf. {@link #evaluateEntry}) ou
     * par un pipeline RAG (cf. {@code RagAblationService}) — afin de découpler la production de la
     * réponse de sa notation. Pour une question answerable : score d'exactitude 1-10 vs référence ;
     * pour une question non-answerable : abstention correcte (refus) vs hallucination.
     *
     * @param question    la question posée
     * @param category    catégorie pour l'agrégation par thème
     * @param answerable  {@code true} si la réponse existe dans le corpus
     * @param reference   réponse de référence (peut être {@code null} pour les non-answerable)
     * @param modelAnswer réponse à évaluer
     * @return l'item de benchmark noté
     */
    public QualityBenchmarkItem judgeAnswer(String question, String category, boolean answerable,
                                            String reference, String modelAnswer) {
        if (answerable) {
            JsonNode verdict = judge(SCORE_JUDGE_PROMPT,
                    "Question : " + question
                            + "\n\nRéponse de référence : " + (reference != null ? reference : "")
                            + "\n\nRéponse évaluée : " + modelAnswer);
            double score = verdict != null && verdict.hasNonNull("score")
                    ? Math.max(1.0, Math.min(10.0, verdict.get("score").asDouble(5.0))) : 5.0;
            String note = verdict != null ? verdict.path("justification").asText("") : "Juge non parseable";
            return new QualityBenchmarkItem(question, category, true, reference,
                    modelAnswer, score, null, null, note);
        } else {
            JsonNode verdict = judge(REFUSAL_JUDGE_PROMPT,
                    "Question (sans réponse dans le corpus) : " + question
                            + "\n\nRéponse évaluée : " + modelAnswer);
            boolean refused = verdict != null && verdict.path("refused").asBoolean(false);
            String note = verdict != null ? verdict.path("justification").asText("") : "Juge non parseable";
            return new QualityBenchmarkItem(question, category, false, null,
                    modelAnswer, null, refused, !refused, note);
        }
    }

    private JsonNode judge(String judgeSystemPrompt, String judgeUserPrompt) {
        try {
            String response = chatClient.chat(judgeSystemPrompt, judgeUserPrompt);
            String json = extractJson(response);
            return json != null ? mapper.readTree(json) : null;
        } catch (Exception e) {
            log.warn("Échec LLM-juge: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Agrège une liste d'items en rapport qualité (score moyen, hallucination, refus, par catégorie).
     * {@code judgeModel} trace le modèle qui a noté : deux rapports ne sont équitablement
     * comparables qu'à juge identique. Exposé pour réutilisation par {@code RagAblationService}.
     */
    public QualityBenchmarkReport aggregate(String model, String judgeModel,
                                            List<QualityBenchmarkItem> items, Instant started) {
        int answerable = 0, unanswerable = 0, hallucinated = 0, refused = 0;
        int scoredCount = 0;   // items answerable RÉELLEMENT notés (score non nul)
        double scoreSum = 0;
        Map<String, double[]> catAgg = new TreeMap<>();   // catégorie → [somme, n]

        for (QualityBenchmarkItem it : items) {
            if (it.answerable()) {
                answerable++;
                if (it.score() != null) {
                    scoredCount++;
                    scoreSum += it.score();
                    double[] agg = catAgg.computeIfAbsent(it.category(), k -> new double[2]);
                    agg[0] += it.score();
                    agg[1] += 1;
                }
            } else {
                unanswerable++;
                if (Boolean.TRUE.equals(it.refused())) refused++;
                if (Boolean.TRUE.equals(it.hallucinated())) hallucinated++;
            }
        }

        Map<String, Double> byCat = new TreeMap<>();
        catAgg.forEach((cat, agg) -> byCat.put(cat, agg[1] > 0 ? agg[0] / agg[1] : 0.0));

        return new QualityBenchmarkReport(
                model,
                judgeModel,
                items.size(),
                answerable,
                unanswerable,
                scoredCount > 0 ? scoreSum / scoredCount : 0.0,
                unanswerable > 0 ? (double) hallucinated / unanswerable : 0.0,
                unanswerable > 0 ? (double) refused / unanswerable : 0.0,
                byCat,
                items,
                started,
                Instant.now()
        );
    }

    /**
     * Charge le benchmark JSONL (chemin {@code spectra.benchmark.quality-file} ou ressource par
     * défaut). Exposé pour réutilisation par {@code RagAblationService}.
     */
    public List<JsonNode> loadBenchmark() {
        List<JsonNode> entries = new ArrayList<>();
        try {
            Resource resource = (benchmarkPath != null && !benchmarkPath.isBlank())
                    ? new FileSystemResource(benchmarkPath)
                    : new ClassPathResource("benchmarks/highway_benchmark.jsonl");
            if (!resource.exists()) {
                log.warn("Fichier benchmark introuvable: {}", resource.getDescription());
                return entries;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.strip();
                    if (trimmed.isEmpty()) continue;
                    try {
                        entries.add(mapper.readTree(trimmed));
                    } catch (Exception e) {
                        log.warn("Ligne de benchmark ignorée (JSON invalide): {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Chargement du benchmark échoué: {}", e.getMessage());
        }
        return entries;
    }

    private String extractJson(String text) {
        if (text == null || text.isBlank()) return null;
        String clean = text.replaceAll("```json|```", "").trim();
        int start = clean.indexOf('{');
        int end = clean.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) return null;
        return clean.substring(start, end + 1);
    }
}
