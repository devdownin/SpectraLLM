package fr.spectra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Capte le feedback 👍/👎 donné aux réponses du Playground.
 *
 * <p>Chaque retour est ajouté en JSONL (prompt + réponse + note) dans
 * {@code data/dataset/playground_feedback.jsonl}. Ce signal de préférence peut
 * ensuite alimenter la construction de paires DPO (un 👍 et un 👎 sur la même
 * question forment une paire chosen/rejected).</p>
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path feedbackFile;

    public FeedbackService(@Value("${spectra.dataset.dir:./data/dataset}") String datasetDir) {
        this.feedbackFile = Path.of(datasetDir).resolve("playground_feedback.jsonl");
    }

    /** Enregistre un feedback. {@code rating} attendu : "UP" ou "DOWN". */
    public synchronized void record(String question, String answer, String rating) {
        record(question, answer, rating, null, null);
    }

    /**
     * Enregistre un feedback enrichi des métadonnées du pipeline et des surcharges de modules
     * de la réponse notée. {@code ragMeta}/{@code overrides} sont optionnels : quand ils sont
     * fournis, un 👎 devient corrélable à la configuration RAG effective (« les pouces rouges
     * arrivent surtout quand le corrective a tout filtré »).
     *
     * @param rating attendu : "UP" ou "DOWN"
     */
    public synchronized void record(String question, String answer, String rating,
                                    Map<String, Object> ragMeta, Map<String, Object> overrides) {
        if (question == null || answer == null || rating == null) return;
        try {
            Files.createDirectories(feedbackFile.getParent());
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("timestamp", Instant.now().toString());
            rec.put("rating", rating);
            rec.put("question", question);
            rec.put("answer", answer);
            if (ragMeta != null && !ragMeta.isEmpty()) rec.put("ragMeta", ragMeta);
            if (overrides != null && !overrides.isEmpty()) rec.put("overrides", overrides);
            Files.writeString(feedbackFile, MAPPER.writeValueAsString(rec) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("[feedback] échec d'enregistrement : {}", e.getMessage());
        }
    }

    // ── Analytique ──────────────────────────────────────────────────────────────

    /** Compte 👍/👎 pour une strate (stratégie ou module). */
    public record RatingCounts(int up, int down) {
        public int total() { return up + down; }
        /** Taux de 👎 (0–1) ; 0 si aucun vote. */
        public double downRate() { return total() == 0 ? 0.0 : (double) down / total(); }
        RatingCounts add(boolean isDown) { return new RatingCounts(up + (isDown ? 0 : 1), down + (isDown ? 1 : 0)); }
    }

    /**
     * Agrégats du feedback Playground. Les ventilations corrèlent les 👎 avec la configuration
     * RAG effective enregistrée par réponse (stratégie retenue, modules ayant agi).
     *
     * @param byStrategy 👍/👎 par {@code ragStrategy}
     * @param byModule   👍/👎 par module ayant réellement agi (drapeaux {@code *Applied} vrais)
     */
    public record FeedbackStats(int total, int up, int down, double downRate,
                                Map<String, RatingCounts> byStrategy,
                                Map<String, RatingCounts> byModule) {}

    /**
     * Lit {@code playground_feedback.jsonl} et agrège les votes. Fichier absent ou lignes
     * malformées → ignorés (dégradation gracieuse : jamais d'échec sur une ligne corrompue).
     */
    public synchronized FeedbackStats aggregate() {
        int up = 0, down = 0;
        Map<String, RatingCounts> byStrategy = new TreeMap<>();
        Map<String, RatingCounts> byModule = new TreeMap<>();

        if (!Files.exists(feedbackFile)) {
            return new FeedbackStats(0, 0, 0, 0.0, byStrategy, byModule);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(feedbackFile);
        } catch (Exception e) {
            log.warn("[feedback] lecture impossible pour l'agrégation : {}", e.getMessage());
            return new FeedbackStats(0, 0, 0, 0.0, byStrategy, byModule);
        }

        for (String line : lines) {
            if (line.isBlank()) continue;
            JsonNode rec;
            try {
                rec = MAPPER.readTree(line);
            } catch (Exception e) {
                continue; // ligne corrompue → ignorée
            }
            boolean isDown = "DOWN".equalsIgnoreCase(rec.path("rating").asText());
            if (isDown) down++; else up++;

            JsonNode ragMeta = rec.get("ragMeta");
            if (ragMeta == null || !ragMeta.isObject()) continue;

            String strategy = ragMeta.path("ragStrategy").asText("");
            if (!strategy.isEmpty()) {
                byStrategy.merge(strategy, new RatingCounts(0, 0).add(isDown), (a, b) -> a.add(isDown));
            }
            for (Iterator<String> it = ragMeta.fieldNames(); it.hasNext(); ) {
                String key = it.next();
                if (key.endsWith("Applied") && ragMeta.path(key).asBoolean(false)) {
                    String module = key.substring(0, key.length() - "Applied".length());
                    byModule.merge(module, new RatingCounts(0, 0).add(isDown), (a, b) -> a.add(isDown));
                }
            }
        }

        int total = up + down;
        double downRate = total == 0 ? 0.0 : (double) down / total;
        return new FeedbackStats(total, up, down, downRate, byStrategy, byModule);
    }
}
