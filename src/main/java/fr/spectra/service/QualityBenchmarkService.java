package fr.spectra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.dto.QualityBenchmarkItem;
import fr.spectra.dto.QualityBenchmarkReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
 * pour évaluer un modèle précis : le modèle actif est alors basculé temporairement puis restauré,
 * ce qui permet de comparer base vs fine-tuné.</p>
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

    private final LlmChatClient chatClient;
    private final String benchmarkPath;

    public QualityBenchmarkService(LlmChatClient chatClient,
                                   @Value("${spectra.benchmark.quality-file:}") String benchmarkPath) {
        this.chatClient = chatClient;
        this.benchmarkPath = benchmarkPath;
    }

    /**
     * Exécute le benchmark qualité. {@code model} optionnel : si fourni, bascule temporairement
     * le modèle actif (puis le restaure) pour permettre la comparaison base vs fine-tuné.
     */
    public QualityBenchmarkReport run(String model) {
        Instant started = Instant.now();
        List<JsonNode> entries = loadBenchmark();
        if (entries.isEmpty()) {
            throw new IllegalStateException(
                    "Benchmark qualité vide ou introuvable. Fournissez un fichier JSONL "
                            + "(spectra.benchmark.quality-file) ou complétez benchmarks/highway_benchmark.jsonl.");
        }

        String previous = chatClient.getActiveModel();
        boolean switched = model != null && !model.isBlank() && !model.equals(previous);
        if (switched) {
            log.info("Benchmark qualité : bascule temporaire du modèle actif {} → {}", previous, model);
            chatClient.setActiveModel(model);
        }
        String evaluatedModel = (model != null && !model.isBlank()) ? model : previous;

        try {
            List<QualityBenchmarkItem> items = new ArrayList<>();
            for (JsonNode entry : entries) {
                items.add(evaluateEntry(entry));
            }
            QualityBenchmarkReport report = aggregate(evaluatedModel, items, started);
            log.info("Benchmark qualité '{}' : score moyen {}/10, hallucination {} %",
                    evaluatedModel, String.format("%.2f", report.avgScore()),
                    String.format("%.0f", report.hallucinationRate() * 100));
            return report;
        } finally {
            if (switched) {
                chatClient.setActiveModel(previous);
                log.info("Benchmark qualité : modèle actif restauré → {}", previous);
            }
        }
    }

    /** Compare deux modèles sur le même benchmark (base vs fine-tuné). */
    public Map<String, QualityBenchmarkReport> compare(String baseline, String candidate) {
        Map<String, QualityBenchmarkReport> out = new LinkedHashMap<>();
        out.put("baseline", run(baseline));
        out.put("candidate", run(candidate));
        return out;
    }

    private QualityBenchmarkItem evaluateEntry(JsonNode entry) {
        String question  = entry.path("question").asText("");
        String category  = entry.path("category").asText("inconnu");
        boolean answerable = entry.path("answerable").asBoolean(true);
        String reference = entry.hasNonNull("reference") ? entry.get("reference").asText() : null;

        String modelAnswer;
        try {
            modelAnswer = chatClient.chat(SYSTEM_PROMPT, question);
        } catch (Exception e) {
            log.warn("Échec appel modèle évalué: {}", e.getMessage());
            return new QualityBenchmarkItem(question, category, answerable, reference,
                    null, null, null, null, "Échec appel modèle: " + e.getMessage());
        }

        return judgeAnswer(question, category, answerable, reference, modelAnswer);
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
     * Exposé pour réutilisation par {@code RagAblationService}.
     */
    public QualityBenchmarkReport aggregate(String model, List<QualityBenchmarkItem> items, Instant started) {
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
