package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.spectra.dto.EvaluationReport;
import fr.spectra.dto.EvaluationRequest;
import fr.spectra.dto.EvaluationScore;
import fr.spectra.model.TrainingPair;
import fr.spectra.service.dataset.DatasetGeneratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
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
    private final Path workDir;
    private final Map<String, EvaluationReport> reports = new ConcurrentHashMap<>();
    private Path reportsFile;

    @Autowired @Lazy
    private EvaluationService self;

    public EvaluationService(DatasetGeneratorService datasetGenerator,
                              LlmChatClient chatClient,
                              @Value("${spectra.fine-tuning.work-dir:./data/fine-tuning}") String workDir) {
        this.datasetGenerator = datasetGenerator;
        this.chatClient = chatClient;
        this.workDir = Path.of(workDir);
    }

    @PostConstruct
    private void init() {
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
        self.runAsync(evalId, request, modelName);
        return evalId;
    }

    public EvaluationReport getReport(String evalId) {
        return reports.get(evalId);
    }

    public List<EvaluationReport> getAllReports() {
        return new ArrayList<>(reports.values());
    }

    @Async
    protected void runAsync(String evalId, EvaluationRequest request, String modelName) {
        try {
            runEvaluation(evalId, request);
        } catch (Exception e) {
            log.error("Évaluation {} échouée: {}", evalId, e.getMessage(), e);
            updateReport(evalId, r -> new EvaluationReport(
                    r.evalId(), "FAILED", r.modelName(), r.jobId(),
                    r.testSetSize(), r.processed(), r.averageScore(),
                    r.scoresByCategory(), r.scores(), e.getMessage(),
                    r.startedAt(), Instant.now()
            ));
        }
    }

    private void runEvaluation(String evalId, EvaluationRequest request) {
        List<TrainingPair> allPairs = datasetGenerator.getAllPairs();
        if (allPairs.isEmpty()) {
            updateReport(evalId, r -> new EvaluationReport(
                    r.evalId(), "FAILED", r.modelName(), r.jobId(),
                    0, 0, 0.0, Map.of(), List.of(),
                    "Dataset vide — générez d'abord des paires via POST /api/dataset/generate.",
                    r.startedAt(), Instant.now()
            ));
            return;
        }

        int testSize = request.testSetSize() != null
                ? Math.max(1, request.testSetSize())
                : Math.min(50, Math.max(5, allPairs.size() / 20));

        List<TrainingPair> shuffled = new ArrayList<>(allPairs);
        Collections.shuffle(shuffled, new Random(42));
        List<TrainingPair> testPairs = shuffled.subList(0, Math.min(testSize, shuffled.size()));

        log.info("Évaluation {}: {} paires de test / {} total", evalId, testPairs.size(), allPairs.size());

        updateReport(evalId, r -> new EvaluationReport(
                r.evalId(), "RUNNING", r.modelName(), r.jobId(),
                testPairs.size(), 0, 0.0, Map.of(), List.of(), null, r.startedAt(), null
        ));

        List<EvaluationScore> scores = new ArrayList<>();
        for (int i = 0; i < testPairs.size(); i++) {
            EvaluationScore score = evaluatePair(testPairs.get(i));
            if (score != null) scores.add(score);

            int done = i + 1;
            List<EvaluationScore> snapshot = List.copyOf(scores);
            double avg = averageScore(snapshot);
            Map<String, Double> byCat = scoresByCategory(snapshot);
            updateReport(evalId, r -> new EvaluationReport(
                    r.evalId(), "RUNNING", r.modelName(), r.jobId(),
                    r.testSetSize(), done, avg, byCat, snapshot, null, r.startedAt(), null
            ));
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

    private EvaluationScore evaluatePair(TrainingPair pair) {
        try {
            String system    = extractRole(pair, "system");
            String question  = extractRole(pair, "user");
            String reference = extractRole(pair, "assistant");
            if (question == null || reference == null) return null;

            String sysPrompt = system != null ? system : "Tu es un assistant spécialisé.";

            String modelAnswer;
            try {
                modelAnswer = chatClient.chat(sysPrompt, question);
            } catch (Exception e) {
                log.warn("Échec appel modèle évalué: {}", e.getMessage());
                return null;
            }

            String judgePrompt = "Question : " + question
                    + "\n\nRéponse de référence : " + reference
                    + "\n\nRéponse évaluée : " + modelAnswer;

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
                    question, reference, modelAnswer,
                    score, justification,
                    pair.metadata().category(),
                    pair.metadata().source()
            );
        } catch (Exception e) {
            log.warn("Erreur inattendue évaluation paire: {}", e.getMessage());
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
