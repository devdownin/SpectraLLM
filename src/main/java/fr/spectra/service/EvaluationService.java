package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.spectra.dto.EvaluationReport;
import fr.spectra.dto.EvaluationRequest;
import fr.spectra.dto.EvaluationScore;
import fr.spectra.dto.ModelComparisonReport;
import fr.spectra.dto.ModelComparisonReport.ModelComparisonEntry;
import fr.spectra.model.TrainingPair;
import fr.spectra.persistence.DocumentModelLinkEntity;
import fr.spectra.persistence.DocumentModelLinkRepository;
import fr.spectra.service.dataset.DatasetGeneratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Évaluation automatique LLM-as-a-judge.
 *
 * <p>Algorithme :
 * <ol>
 *   <li>Échantillonne 5 % du dataset (min 5, max 50 paires) comme jeu de test.</li>
 *   <li>Pour chaque paire, interroge le modèle actif et compare à la réponse de référence.</li>
 *   <li>Le même LLM sert de juge : il note la réponse de 1 à 10.</li>
 *   <li>Les scores sont agrégés par catégorie (qa, summary, classification, negative).</li>
 * </ol>
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String JUDGE_SYSTEM_PROMPT = """
            Tu es un évaluateur expert en qualité de réponses LLM.
            Compare la réponse fournie à la réponse de référence selon ces critères :
            - Exactitude (0-4) : La réponse est-elle correcte et sans erreur factuelle ?
            - Complétude (0-3) : Les points essentiels de la référence sont-ils couverts ?
            - Clarté (0-3) : La réponse est-elle bien formulée et concise ?

            Réponds UNIQUEMENT avec ce JSON (rien d'autre) :
            {"score": <entier 1-10>, "justification": "<une phrase courte en français>"}
            """;

    private final DatasetGeneratorService datasetGenerator;
    private final LlmChatClient chatClient;
    private final DocumentModelLinkRepository linkRepository;
    private final Path workDir;
    /** Nombre maximal de rapports COMPLETED conservés (les plus anciens sont évincés). */
    private final int maxCompletedReports;
    /** Modèle-juge neutre (vide = le modèle évalué se juge lui-même, comportement par défaut). */
    private final String judgeModel;
    private final Map<String, EvaluationReport> reports = new ConcurrentHashMap<>();
    private final Set<String> cancelledEvals = ConcurrentHashMap.newKeySet();
    private Path reportsFile;

    @Autowired @Lazy
    private EvaluationService self;

    public EvaluationService(DatasetGeneratorService datasetGenerator,
                              LlmChatClient chatClient,
                              DocumentModelLinkRepository linkRepository,
                              @Value("${spectra.fine-tuning.work-dir:./data/fine-tuning}") String workDir,
                              @Value("${spectra.evaluation.max-completed-reports:200}") int maxCompletedReports,
                              @Value("${spectra.evaluation.judge-model:}") String judgeModel) {
        this.datasetGenerator = datasetGenerator;
        this.chatClient = chatClient;
        this.linkRepository = linkRepository;
        this.workDir = Path.of(workDir);
        this.maxCompletedReports = maxCompletedReports > 0 ? maxCompletedReports : 200;
        this.judgeModel = judgeModel != null ? judgeModel.trim() : "";
    }

    @PostConstruct
    void init() {
        reportsFile = workDir.resolve("evaluations.json");
        if (Files.exists(reportsFile)) {
            try {
                var type = mapper.getTypeFactory()
                        .constructMapType(Map.class, String.class, EvaluationReport.class);
                Map<String, EvaluationReport> loaded = mapper.readValue(reportsFile.toFile(), type);
                reports.putAll(loaded);
                log.info("Évaluations restaurées: {} rapports", loaded.size());
            } catch (Exception e) {
                log.warn("Impossible de charger les évaluations persistées: {}", e.getMessage());
            }
        }
    }

    private void persistReports() {
        if (reportsFile == null) return;
        try {
            Files.createDirectories(workDir);
            mapper.writerWithDefaultPrettyPrinter().writeValue(reportsFile.toFile(), reports);
        } catch (Exception e) {
            log.warn("Échec persistance évaluations: {}", e.getMessage());
        }
    }

    public String submit(EvaluationRequest request) {
        String evalId = UUID.randomUUID().toString();
        String modelName = (request.modelName() != null && !request.modelName().isBlank())
                ? request.modelName()
                : chatClient.getActiveModel();
        reports.put(evalId, EvaluationReport.pending(evalId, modelName, request.jobId()));
        persistReports();
        (self != null ? self : this).runAsync(evalId, request, modelName);
        return evalId;
    }

    /**
     * Lance l'évaluation d'une liste de modèles sur un <strong>même jeu de test</strong>,
     * de façon séquentielle, pour permettre une comparaison équitable de leurs gains.
     *
     * <p>Chaque modèle est chargé tour à tour (bascule du modèle actif), évalué, puis
     * le modèle initialement actif est restauré à la fin. La bascule n'est réellement
     * effective que si l'orchestrateur runtime llama.cpp est activé.
     *
     * @param modelNames modèles à évaluer (les doublons sont ignorés, l'ordre est conservé)
     * @param testSetSize taille du jeu de test commun (défaut : 5 % du dataset)
     * @return identifiants des évaluations créées, dans l'ordre des modèles
     * @throws IllegalArgumentException si {@code modelNames} est vide
     */
    public List<String> submitBatch(List<String> modelNames, Integer testSetSize) {
        if (modelNames == null || modelNames.isEmpty()) {
            throw new IllegalArgumentException("Au moins un modèle est requis pour une évaluation par lot.");
        }
        LinkedHashMap<String, String> evalIdByModel = new LinkedHashMap<>();
        List<String> evalIds = new ArrayList<>();
        for (String model : new LinkedHashSet<>(modelNames)) {
            if (model == null || model.isBlank()) continue;
            String evalId = UUID.randomUUID().toString();
            reports.put(evalId, EvaluationReport.pending(evalId, model, null));
            evalIds.add(evalId);
            evalIdByModel.put(evalId, model);
        }
        if (evalIds.isEmpty()) {
            throw new IllegalArgumentException("Aucun nom de modèle valide fourni.");
        }
        persistReports();
        (self != null ? self : this).runBatchAsync(evalIdByModel, testSetSize);
        return evalIds;
    }

    public EvaluationReport getReport(String evalId) {
        return reports.get(evalId);
    }

    public List<EvaluationReport> getAllReports() {
        return new ArrayList<>(reports.values());
    }

    /**
     * Compare plusieurs rapports d'évaluation pour mesurer les gains et différences
     * de performance entre modèles personnalisés.
     *
     * <p>Calcule, pour chaque modèle, le delta de score (global et par catégorie) par
     * rapport à un modèle de référence, et y rattache le nombre de documents qui ont
     * nourri le modèle (liens GED {@code TRAINED_ON} / {@code EVALUATED_ON}).
     *
     * @param evalIds    identifiants des évaluations à comparer (≥ 1)
     * @param baselineId évaluation de référence pour les deltas ; si absente de la
     *                   sélection, le modèle ayant le meilleur score global est retenu
     * @return rapport de comparaison, entrées triées par score global décroissant
     * @throws IllegalArgumentException si {@code evalIds} est vide
     * @throws NoSuchElementException   si un identifiant est inconnu
     */
    public ModelComparisonReport compareReports(List<String> evalIds, String baselineId) {
        if (evalIds == null || evalIds.isEmpty()) {
            throw new IllegalArgumentException("Au moins un evalId est requis pour comparer.");
        }

        List<EvaluationReport> selected = new ArrayList<>();
        for (String id : evalIds) {
            EvaluationReport report = reports.get(id);
            if (report == null) {
                throw new NoSuchElementException("Évaluation inconnue : " + id);
            }
            selected.add(report);
        }

        EvaluationReport baseline = selected.stream()
                .filter(r -> r.evalId().equals(baselineId))
                .findFirst()
                .orElseGet(() -> selected.stream()
                        .max(Comparator.comparingDouble(EvaluationReport::averageScore))
                        .orElse(selected.get(0)));

        // Union ordonnée des catégories rencontrées (préserve l'ordre d'apparition).
        Set<String> categories = new LinkedHashSet<>();
        selected.forEach(r -> categories.addAll(r.scoresByCategory().keySet()));

        List<ModelComparisonEntry> entries = new ArrayList<>();
        for (EvaluationReport report : selected) {
            Map<String, Double> deltaByCategory = new LinkedHashMap<>();
            for (String category : categories) {
                Double mine = report.scoresByCategory().get(category);
                Double base = baseline.scoresByCategory().get(category);
                if (mine != null && base != null) {
                    deltaByCategory.put(category, round(mine - base));
                }
            }

            entries.add(new ModelComparisonEntry(
                    report.evalId(),
                    report.modelName(),
                    report.status(),
                    report.processed(),
                    round(report.averageScore()),
                    report.scoresByCategory(),
                    report.completedAt(),
                    countLinks(report.modelName(), DocumentModelLinkEntity.LinkType.TRAINED_ON),
                    countLinks(report.modelName(), DocumentModelLinkEntity.LinkType.EVALUATED_ON),
                    report.evalId().equals(baseline.evalId()),
                    round(report.averageScore() - baseline.averageScore()),
                    deltaByCategory
            ));
        }

        entries.sort(Comparator.comparingDouble(ModelComparisonEntry::averageScore).reversed());
        return new ModelComparisonReport(baseline.modelName(), new ArrayList<>(categories), entries);
    }

    /** Compte les documents liés à un modèle pour un type de lien GED donné. */
    private long countLinks(String modelName, DocumentModelLinkEntity.LinkType type) {
        if (linkRepository == null || modelName == null) {
            return 0L;
        }
        return linkRepository.findByModelName(modelName).stream()
                .filter(link -> link.getLinkType() == type)
                .count();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public boolean cancelEvaluation(String evalId) {
        EvaluationReport report = reports.get(evalId);
        if (report == null) return false;
        if ("COMPLETED".equals(report.status()) || "FAILED".equals(report.status())
                || "CANCELLED".equals(report.status())) return false;
        cancelledEvals.add(evalId);
        updateReport(evalId, r -> new EvaluationReport(
                r.evalId(), "CANCELLED", r.modelName(), r.jobId(),
                r.testSetSize(), r.processed(), r.averageScore(),
                r.scoresByCategory(), r.scores(), "Annulé par l'utilisateur",
                r.startedAt(), Instant.now()
        ));
        persistReports();
        return true;
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void cleanupOldReports() {
        Instant cutoff = Instant.now().minusSeconds(3600);
        // Les rapports COMPLETED sont conservés (nécessaires aux comparaisons) ; on ne
        // purge que les échecs/annulations anciens, puis on borne le nombre de COMPLETED.
        boolean removed = reports.entrySet().removeIf(e -> {
            EvaluationReport r = e.getValue();
            return ("FAILED".equals(r.status()) || "CANCELLED".equals(r.status()))
                    && r.completedAt() != null && r.completedAt().isBefore(cutoff);
        });
        removed |= evictExcessCompleted();
        cancelledEvals.removeIf(id -> !reports.containsKey(id));
        if (removed) persistReports();
    }

    /**
     * Évince les rapports COMPLETED les plus anciens au-delà de {@link #maxCompletedReports}.
     * @return vrai si au moins un rapport a été supprimé
     */
    private boolean evictExcessCompleted() {
        List<EvaluationReport> completed = reports.values().stream()
                .filter(r -> "COMPLETED".equals(r.status()))
                .sorted(Comparator.comparing(r -> r.completedAt() != null ? r.completedAt() : Instant.EPOCH))
                .toList();
        int excess = completed.size() - maxCompletedReports;
        if (excess <= 0) return false;
        for (int i = 0; i < excess; i++) {
            reports.remove(completed.get(i).evalId());
        }
        return true;
    }

    @Async
    protected void runAsync(String evalId, EvaluationRequest request, String modelName) {
        try {
            runEvaluation(evalId, request);
        } catch (Exception e) {
            log.error("Évaluation {} échouée: {}", evalId, e.getMessage(), e);
            failReport(evalId, e.getMessage());
        }
    }

    @Async
    protected void runBatchAsync(LinkedHashMap<String, String> evalIdByModel, Integer testSetSize) {
        List<TrainingPair> testPairs = sampleTestSet(testSetSize);
        if (testPairs.isEmpty()) {
            evalIdByModel.keySet().forEach(id -> failReport(id,
                    "Dataset vide — générez d'abord des paires via POST /api/dataset/generate."));
            return;
        }

        String original = chatClient.getActiveModel();
        log.info("Évaluation par lot : {} modèles sur {} paires de test partagées",
                evalIdByModel.size(), testPairs.size());
        try {
            for (Map.Entry<String, String> entry : evalIdByModel.entrySet()) {
                String evalId = entry.getKey();
                String model = entry.getValue();
                if (cancelledEvals.contains(evalId)) {
                    continue;
                }
                if (!activateTargetModel(evalId, model, chatClient.getActiveModel())) {
                    continue;
                }
                try {
                    runEvaluationOnPairs(evalId, testPairs);
                } catch (Exception e) {
                    log.error("Évaluation batch {} échouée: {}", evalId, e.getMessage(), e);
                    failReport(evalId, e.getMessage());
                }
            }
        } finally {
            restoreModel(original);
        }
    }

    private void runEvaluation(String evalId, EvaluationRequest request) {
        List<TrainingPair> testPairs = sampleTestSet(request.testSetSize());
        if (testPairs.isEmpty()) {
            failReport(evalId, "Dataset vide — générez d'abord des paires via POST /api/dataset/generate.");
            return;
        }

        String modelName = reportModelName(evalId);
        String original = chatClient.getActiveModel();
        if (!activateTargetModel(evalId, modelName, original)) {
            return;
        }
        try {
            runEvaluationOnPairs(evalId, testPairs);
        } finally {
            restoreModel(original);
        }
    }

    /**
     * Échantillonne un jeu de test reproductible (seed fixe) sur le dataset courant.
     * @return liste indépendante (réutilisable), vide si le dataset est vide
     */
    private List<TrainingPair> sampleTestSet(Integer requestedSize) {
        List<TrainingPair> allPairs = datasetGenerator.getAllPairs();
        if (allPairs.isEmpty()) {
            return List.of();
        }
        int testSize = requestedSize != null
                ? Math.max(1, requestedSize)
                : Math.min(50, Math.max(5, allPairs.size() / 20));
        List<TrainingPair> shuffled = new ArrayList<>(allPairs);
        Collections.shuffle(shuffled, new Random(42));
        return new ArrayList<>(shuffled.subList(0, Math.min(testSize, shuffled.size())));
    }

    /** Exécute la boucle d'évaluation sur un jeu de test, avec le modèle actuellement actif. */
    private void runEvaluationOnPairs(String evalId, List<TrainingPair> testPairs) {
        log.info("Évaluation {}: {} paires de test", evalId, testPairs.size());

        updateReport(evalId, r -> new EvaluationReport(
                r.evalId(), "RUNNING", r.modelName(), r.jobId(),
                testPairs.size(), 0, 0.0, Map.of(), List.of(), null, r.startedAt(), null
        ));

        String evaluatedModel = chatClient.getActiveModel();
        boolean useNeutralJudge = !judgeModel.isEmpty() && !judgeModel.equals(evaluatedModel);

        List<EvaluationScore> scores = useNeutralJudge
                ? scoreWithNeutralJudge(evalId, testPairs, evaluatedModel)
                : scoreSelfJudge(evalId, testPairs);
        if (scores == null) {
            return; // annulée ou échec — rapport déjà mis à jour
        }

        List<EvaluationScore> finalScores = List.copyOf(scores);
        double finalAvg = averageScore(finalScores);
        Map<String, Double> finalByCat = scoresByCategory(finalScores);
        log.info("Évaluation {} terminée — score moyen: {}/10 ({} paires)",
                evalId, String.format("%.2f", finalAvg), finalScores.size());

        updateReport(evalId, r -> new EvaluationReport(
                r.evalId(), "COMPLETED", r.modelName(), r.jobId(),
                r.testSetSize(), finalScores.size(), finalAvg,
                finalByCat, finalScores, null, r.startedAt(), Instant.now()
        ));
    }

    /**
     * Notation où le modèle évalué se juge lui-même (comportement par défaut) :
     * génération + jugement avec le modèle actif, paire par paire.
     * @return scores accumulés, ou {@code null} si l'évaluation a été annulée
     */
    private List<EvaluationScore> scoreSelfJudge(String evalId, List<TrainingPair> testPairs) {
        List<EvaluationScore> scores = new ArrayList<>();
        for (int i = 0; i < testPairs.size(); i++) {
            if (cancelledEvals.contains(evalId)) {
                log.info("Évaluation {} annulée à l'itération {}", evalId, i);
                return null;
            }
            Generated generated = generateAnswer(testPairs.get(i));
            EvaluationScore score = generated != null ? judge(generated) : null;
            if (score != null) scores.add(score);
            publishRunning(evalId, i + 1, scores);
        }
        return scores;
    }

    /**
     * Notation par un modèle-juge neutre, en deux phases pour éviter de recharger le
     * serveur à chaque paire :
     * <ol>
     *   <li>génère toutes les réponses avec le modèle évalué (actif) ;</li>
     *   <li>bascule une seule fois vers le modèle-juge et note chaque réponse.</li>
     * </ol>
     * @return scores accumulés, ou {@code null} si annulée / échec d'activation du juge
     */
    private List<EvaluationScore> scoreWithNeutralJudge(String evalId, List<TrainingPair> testPairs,
                                                        String evaluatedModel) {
        log.info("Évaluation {} : juge neutre '{}' (modèle évalué '{}')",
                evalId, judgeModel, evaluatedModel);

        // Phase 1 — génération des réponses avec le modèle évalué.
        List<Generated> generated = new ArrayList<>();
        for (int i = 0; i < testPairs.size(); i++) {
            if (cancelledEvals.contains(evalId)) {
                log.info("Évaluation {} annulée (génération) à l'itération {}", evalId, i);
                return null;
            }
            Generated g = generateAnswer(testPairs.get(i));
            if (g != null) generated.add(g);
        }

        // Phase 2 — bascule vers le juge neutre puis notation.
        if (!activateTargetModel(evalId, judgeModel, evaluatedModel)) {
            return null; // rapport déjà marqué FAILED
        }
        List<EvaluationScore> scores = new ArrayList<>();
        for (int i = 0; i < generated.size(); i++) {
            if (cancelledEvals.contains(evalId)) {
                log.info("Évaluation {} annulée (notation) à l'itération {}", evalId, i);
                return null;
            }
            EvaluationScore score = judge(generated.get(i));
            if (score != null) scores.add(score);
            publishRunning(evalId, i + 1, scores);
        }
        // Le modèle évalué/initial est restauré par l'appelant (runEvaluation / runBatchAsync).
        return scores;
    }

    /** Publie un instantané RUNNING (progression + moyenne courante). */
    private void publishRunning(String evalId, int done, List<EvaluationScore> scores) {
        List<EvaluationScore> snapshot = List.copyOf(scores);
        double avg = averageScore(snapshot);
        Map<String, Double> byCat = scoresByCategory(snapshot);
        updateReport(evalId, r -> new EvaluationReport(
                r.evalId(), "RUNNING", r.modelName(), r.jobId(),
                r.testSetSize(), done, avg, byCat, snapshot, null, r.startedAt(), null
        ));
    }

    /** Nom du modèle ciblé par un rapport (sinon modèle actif). */
    private String reportModelName(String evalId) {
        EvaluationReport r = reports.get(evalId);
        return r != null ? r.modelName() : chatClient.getActiveModel();
    }

    /**
     * Bascule le modèle servi vers {@code modelName} s'il diffère du modèle courant.
     * @return vrai si le modèle est prêt à être évalué ; faux en cas d'échec (rapport marqué FAILED)
     */
    private boolean activateTargetModel(String evalId, String modelName, String current) {
        if (modelName == null || modelName.equals(current)) {
            return true;
        }
        try {
            chatClient.setActiveModel(modelName);
            return true;
        } catch (Exception e) {
            log.warn("Évaluation {} : impossible d'activer le modèle '{}' : {}", evalId, modelName, e.getMessage());
            failReport(evalId, "Impossible de charger le modèle '" + modelName + "' : " + e.getMessage());
            return false;
        }
    }

    /** Restaure le modèle initialement actif (best-effort). */
    private void restoreModel(String original) {
        try {
            if (original != null && !original.equals(chatClient.getActiveModel())) {
                chatClient.setActiveModel(original);
            }
        } catch (Exception e) {
            log.warn("Impossible de restaurer le modèle actif '{}' : {}", original, e.getMessage());
        }
    }

    /** Marque un rapport comme FAILED avec un message d'erreur. */
    private void failReport(String evalId, String message) {
        updateReport(evalId, r -> new EvaluationReport(
                r.evalId(), "FAILED", r.modelName(), r.jobId(),
                r.testSetSize(), r.processed(), r.averageScore(),
                r.scoresByCategory(), r.scores(), message, r.startedAt(), Instant.now()
        ));
    }

    /** Réponse générée par le modèle évalué pour une paire, avant notation. */
    private record Generated(String question, String reference, String modelAnswer,
                             String category, String source) {}

    /** Interroge le modèle actif (évalué) pour produire une réponse à la question de la paire. */
    private Generated generateAnswer(TrainingPair pair) {
        try {
            String system    = extractRole(pair, "system");
            String question  = extractRole(pair, "user");
            String reference = extractRole(pair, "assistant");
            if (question == null || reference == null) return null;

            String sysPrompt = system != null ? system : fr.spectra.model.AssistantPersona.SYSTEM_PROMPT;

            String modelAnswer;
            try {
                modelAnswer = chatClient.chat(sysPrompt, question);
            } catch (Exception e) {
                log.warn("Échec appel modèle évalué: {}", e.getMessage());
                return null;
            }
            return new Generated(question, reference, modelAnswer,
                    pair.metadata().category(), pair.metadata().source());
        } catch (Exception e) {
            log.warn("Erreur inattendue génération réponse: {}", e.getMessage());
            return null;
        }
    }

    /** Note une réponse générée via le modèle-juge actif (LLM-as-a-judge). */
    private EvaluationScore judge(Generated g) {
        try {
            String judgePrompt = "Question : " + g.question()
                    + "\n\nRéponse de référence : " + g.reference()
                    + "\n\nRéponse évaluée : " + g.modelAnswer();

            String judgeResponse;
            try {
                judgeResponse = chatClient.chat(JUDGE_SYSTEM_PROMPT, judgePrompt);
            } catch (Exception e) {
                log.warn("Échec appel LLM-juge: {}", e.getMessage());
                return null;
            }

            String json = extractJson(judgeResponse);
            if (json == null) {
                log.debug("Réponse juge non parseable: {}", judgeResponse);
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(json, Map.class);
            Object scoreObj = parsed.get("score");
            String justification = (String) parsed.getOrDefault("justification", "");
            double score = scoreObj instanceof Number n ? n.doubleValue() : 5.0;
            score = Math.max(1.0, Math.min(10.0, score));

            return new EvaluationScore(
                    g.question(), g.reference(), g.modelAnswer(),
                    score, justification, g.category(), g.source()
            );
        } catch (Exception e) {
            log.warn("Erreur inattendue notation paire: {}", e.getMessage());
            return null;
        }
    }

    private String extractRole(TrainingPair pair, String role) {
        return pair.conversations().stream()
                .filter(m -> role.equals(m.role()))
                .map(TrainingPair.Message::content)
                .findFirst().orElse(null);
    }

    private String extractJson(String text) {
        if (text == null || text.isBlank()) return null;
        String clean = text.replaceAll("```json|```", "").trim();
        int start = clean.indexOf('{');
        int end   = clean.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) return null;
        return clean.substring(start, end + 1);
    }

    private double averageScore(List<EvaluationScore> scores) {
        return scores.isEmpty() ? 0.0
                : scores.stream().mapToDouble(EvaluationScore::score).average().orElse(0.0);
    }

    private Map<String, Double> scoresByCategory(List<EvaluationScore> scores) {
        return scores.stream().collect(Collectors.groupingBy(
                EvaluationScore::category,
                TreeMap::new,
                Collectors.averagingDouble(EvaluationScore::score)
        ));
    }

    private void updateReport(String evalId, UnaryOperator<EvaluationReport> updater) {
        reports.computeIfPresent(evalId, (k, v) -> updater.apply(v));
        persistReports();
    }
}
