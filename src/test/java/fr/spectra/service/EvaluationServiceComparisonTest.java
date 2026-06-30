package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.spectra.dto.EvaluationReport;
import fr.spectra.dto.ModelComparisonReport;
import fr.spectra.persistence.DocumentModelLinkEntity;
import fr.spectra.persistence.DocumentModelLinkEntity.LinkType;
import fr.spectra.persistence.DocumentModelLinkRepository;
import fr.spectra.service.dataset.DatasetGeneratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de {@link EvaluationService#compareReports} : deltas vs baseline,
 * sélection de la référence et rattachement documentaire (liens GED).
 */
class EvaluationServiceComparisonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @TempDir
    Path tempDir;

    private DocumentModelLinkRepository linkRepository;
    private EvaluationService service;

    @BeforeEach
    void setUp() throws Exception {
        Map<String, EvaluationReport> seeded = Map.of(
                "eval-base", completed("eval-base", "model-base", 7.0,
                        Map.of("qa", 7.0, "summary", 6.0)),
                "eval-tuned", completed("eval-tuned", "model-tuned", 8.5,
                        Map.of("qa", 9.0, "summary", 8.0))
        );
        Files.createDirectories(tempDir);
        MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(tempDir.resolve("evaluations.json").toFile(), seeded);

        DatasetGeneratorService datasetGenerator = mock(DatasetGeneratorService.class);
        LlmChatClient chatClient = mock(LlmChatClient.class);
        linkRepository = mock(DocumentModelLinkRepository.class);
        when(linkRepository.findByModelName("model-base")).thenReturn(List.of());
        when(linkRepository.findByModelName("model-tuned")).thenReturn(List.of(
                link("model-tuned", LinkType.TRAINED_ON),
                link("model-tuned", LinkType.TRAINED_ON),
                link("model-tuned", LinkType.EVALUATED_ON)
        ));

        service = new EvaluationService(datasetGenerator, chatClient, linkRepository, tempDir.toString(), 200);
        service.init();
    }

    @Test
    void comparesDeltasAgainstExplicitBaseline() {
        ModelComparisonReport report =
                service.compareReports(List.of("eval-base", "eval-tuned"), "eval-base");

        assertThat(report.baselineModel()).isEqualTo("model-base");
        assertThat(report.categories()).containsExactlyInAnyOrder("qa", "summary");

        // Entrées triées par score décroissant : le modèle fine-tuné en tête.
        assertThat(report.models()).hasSize(2);
        var tuned = report.models().get(0);
        var base = report.models().get(1);

        assertThat(tuned.modelName()).isEqualTo("model-tuned");
        assertThat(tuned.baseline()).isFalse();
        assertThat(tuned.deltaVsBaseline()).isEqualTo(1.5);
        assertThat(tuned.deltaByCategory()).containsEntry("qa", 2.0).containsEntry("summary", 2.0);
        assertThat(tuned.trainedOnDocs()).isEqualTo(2L);
        assertThat(tuned.evaluatedOnDocs()).isEqualTo(1L);

        assertThat(base.baseline()).isTrue();
        assertThat(base.deltaVsBaseline()).isZero();
        assertThat(base.trainedOnDocs()).isZero();
    }

    @Test
    void picksBestScoringModelAsBaselineWhenUnspecified() {
        ModelComparisonReport report =
                service.compareReports(List.of("eval-base", "eval-tuned"), null);

        assertThat(report.baselineModel()).isEqualTo("model-tuned");
        var base = report.models().stream().filter(m -> m.modelName().equals("model-base")).findFirst().orElseThrow();
        assertThat(base.deltaVsBaseline()).isEqualTo(-1.5);
    }

    @Test
    void rejectsUnknownEvalId() {
        assertThatThrownBy(() -> service.compareReports(List.of("missing"), null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void rejectsEmptySelection() {
        assertThatThrownBy(() -> service.compareReports(List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static EvaluationReport completed(String evalId, String modelName,
                                              double avg, Map<String, Double> byCategory) {
        return new EvaluationReport(
                evalId, "COMPLETED", modelName, null,
                byCategory.size(), byCategory.size(), avg, byCategory, List.of(),
                null, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:05:00Z"));
    }

    private static DocumentModelLinkEntity link(String modelName, LinkType type) {
        return new DocumentModelLinkEntity("sha-" + type.name(), modelName, type, Instant.now());
    }
}
