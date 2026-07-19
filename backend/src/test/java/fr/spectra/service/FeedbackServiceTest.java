package fr.spectra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests de {@link FeedbackService} — journalisation JSONL, enrichissement pipeline. */
class FeedbackServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void record_basic_writesJsonlLine(@TempDir Path dir) throws Exception {
        FeedbackService svc = new FeedbackService(dir.toString());
        svc.record("Question ?", "Réponse.", "UP");

        JsonNode rec = readSingleRecord(dir);
        assertThat(rec.get("rating").asText()).isEqualTo("UP");
        assertThat(rec.get("question").asText()).isEqualTo("Question ?");
        assertThat(rec.get("answer").asText()).isEqualTo("Réponse.");
        assertThat(rec.has("ragMeta")).isFalse();
        assertThat(rec.has("overrides")).isFalse();
        assertThat(rec.has("timestamp")).isTrue();
    }

    @Test
    void record_withPipelineMeta_persistsRagMetaAndOverrides(@TempDir Path dir) throws Exception {
        FeedbackService svc = new FeedbackService(dir.toString());
        Map<String, Object> ragMeta = Map.of("ragStrategy", "STANDARD", "correctiveApplied", true);
        Map<String, Object> overrides = Map.of("rerank", false);

        svc.record("Question ?", "Réponse.", "DOWN", ragMeta, overrides);

        JsonNode rec = readSingleRecord(dir);
        assertThat(rec.get("rating").asText()).isEqualTo("DOWN");
        assertThat(rec.get("ragMeta").get("ragStrategy").asText()).isEqualTo("STANDARD");
        assertThat(rec.get("ragMeta").get("correctiveApplied").asBoolean()).isTrue();
        assertThat(rec.get("overrides").get("rerank").asBoolean()).isFalse();
    }

    @Test
    void record_emptyMaps_areOmitted(@TempDir Path dir) throws Exception {
        FeedbackService svc = new FeedbackService(dir.toString());
        svc.record("Q", "A", "UP", Map.of(), Map.of());

        JsonNode rec = readSingleRecord(dir);
        assertThat(rec.has("ragMeta")).isFalse();
        assertThat(rec.has("overrides")).isFalse();
    }

    @Test
    void record_nullRequiredField_writesNothing(@TempDir Path dir) throws Exception {
        FeedbackService svc = new FeedbackService(dir.toString());
        svc.record(null, "A", "UP");

        Path file = dir.resolve("playground_feedback.jsonl");
        assertThat(Files.exists(file)).isFalse();
    }

    private static JsonNode readSingleRecord(Path dir) throws Exception {
        Path file = dir.resolve("playground_feedback.jsonl");
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(1);
        return MAPPER.readTree(lines.get(0));
    }
}
