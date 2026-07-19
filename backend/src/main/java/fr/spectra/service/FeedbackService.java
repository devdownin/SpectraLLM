package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

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
}
