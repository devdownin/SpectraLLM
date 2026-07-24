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

    // ── aggregate() ─────────────────────────────────────────────────────────────

    @Test
    void aggregate_noFile_returnsEmptyStats() {
        FeedbackService svc = new FeedbackService(java.util.UUID.randomUUID().toString());
        FeedbackService.FeedbackStats stats = svc.aggregate();
        assertThat(stats.total()).isZero();
        assertThat(stats.downRate()).isZero();
        assertThat(stats.byStrategy()).isEmpty();
        assertThat(stats.byModule()).isEmpty();
    }

    @Test
    void aggregate_countsRatingsAndBreaksDownByStrategyAndModule(@TempDir Path dir) {
        FeedbackService svc = new FeedbackService(dir.toString());
        // 3 votes : 2 STANDARD (1 up avec rerank, 1 down avec rerank+corrective), 1 AGENTIC up.
        svc.record("q1", "a1", "UP", Map.of("ragStrategy", "STANDARD", "rerankApplied", true), null);
        svc.record("q2", "a2", "DOWN", Map.of("ragStrategy", "STANDARD", "rerankApplied", true, "correctiveApplied", true), null);
        svc.record("q3", "a3", "UP", Map.of("ragStrategy", "AGENTIC"), null);

        FeedbackService.FeedbackStats stats = svc.aggregate();

        assertThat(stats.total()).isEqualTo(3);
        assertThat(stats.up()).isEqualTo(2);
        assertThat(stats.down()).isEqualTo(1);
        assertThat(stats.downRate()).isEqualTo(1.0 / 3, org.assertj.core.api.Assertions.within(1e-9));

        assertThat(stats.byStrategy().get("STANDARD"))
                .isEqualTo(new FeedbackService.RatingCounts(1, 1));
        assertThat(stats.byStrategy().get("AGENTIC"))
                .isEqualTo(new FeedbackService.RatingCounts(1, 0));

        // rerank a agi sur les 2 réponses STANDARD → 1 up, 1 down.
        assertThat(stats.byModule().get("rerank"))
                .isEqualTo(new FeedbackService.RatingCounts(1, 1));
        // corrective seulement sur la réponse notée 👎.
        FeedbackService.RatingCounts corrective = stats.byModule().get("corrective");
        assertThat(corrective).isEqualTo(new FeedbackService.RatingCounts(0, 1));
        assertThat(corrective.downRate()).isEqualTo(1.0);
    }

    @Test
    void aggregate_skipsMalformedLines(@TempDir Path dir) throws Exception {
        FeedbackService svc = new FeedbackService(dir.toString());
        svc.record("q", "a", "UP");
        // Injecte une ligne corrompue et une ligne vide.
        Path file = dir.resolve("playground_feedback.jsonl");
        Files.writeString(file, "{ this is not json\n\n", java.nio.file.StandardOpenOption.APPEND);

        FeedbackService.FeedbackStats stats = svc.aggregate();
        assertThat(stats.total()).isEqualTo(1);
        assertThat(stats.up()).isEqualTo(1);
    }

    private static JsonNode readSingleRecord(Path dir) throws Exception {
        Path file = dir.resolve("playground_feedback.jsonl");
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(1);
        return MAPPER.readTree(lines.get(0));
    }
}
