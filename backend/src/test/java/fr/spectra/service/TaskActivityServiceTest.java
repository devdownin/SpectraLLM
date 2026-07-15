package fr.spectra.service;

import fr.spectra.dto.EvaluationReport;
import fr.spectra.dto.FineTuningJob;
import fr.spectra.dto.IngestionTask;
import fr.spectra.dto.InstallationJob;
import fr.spectra.service.dataset.DatasetGeneratorService;
import fr.spectra.service.dataset.DpoGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L'instantané agrégé doit être compact (pas de rapports lourds), fidèle aux noms de
 * champs des endpoints REST (le client normalise SSE et REST avec le même code) et
 * best-effort (une source en panne n'efface pas les autres).
 */
class TaskActivityServiceTest {

    private IngestionService ingestionService;
    private DatasetGeneratorService datasetGenerator;
    private DpoGenerationService dpoService;
    private FineTuningService fineTuningService;
    private EvaluationService evaluationService;
    private QualityBenchmarkService qualityBenchmarkService;
    private RagAblationService ragAblationService;
    private LlmFitService llmFitService;
    private TaskActivityService service;

    @BeforeEach
    void setUp() {
        ingestionService = mock(IngestionService.class);
        datasetGenerator = mock(DatasetGeneratorService.class);
        dpoService = mock(DpoGenerationService.class);
        fineTuningService = mock(FineTuningService.class);
        evaluationService = mock(EvaluationService.class);
        qualityBenchmarkService = mock(QualityBenchmarkService.class);
        ragAblationService = mock(RagAblationService.class);
        llmFitService = mock(LlmFitService.class);

        when(ingestionService.getAllTasks()).thenReturn(List.of());
        when(datasetGenerator.getAllTasks()).thenReturn(List.of());
        when(dpoService.getAllTasks()).thenReturn(List.of());
        when(fineTuningService.getAllJobs()).thenReturn(List.of());
        when(evaluationService.getAllReports()).thenReturn(List.of());
        when(evaluationService.getAllAbReports()).thenReturn(List.of());
        when(qualityBenchmarkService.getCompareJobs()).thenReturn(List.of());
        when(ragAblationService.getJobs()).thenReturn(List.of());
        when(llmFitService.getInstallations()).thenReturn(List.of());

        service = new TaskActivityService(ingestionService, datasetGenerator, dpoService,
                fineTuningService, evaluationService, qualityBenchmarkService, ragAblationService,
                llmFitService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshotContainsAllTaskFamilies() {
        Map<String, Object> snap = service.snapshot();
        assertThat(snap).containsKeys("ingest", "dataset", "dpo", "training",
                "evaluations", "ab", "installs", "benchmarks", "ablations");
        assertThat((List<Map<String, Object>>) snap.get("ingest")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestionTaskIsCompactedWithRestFieldNames() {
        IngestionTask task = IngestionTask.pending("t1", List.of("a.pdf"))
                .processing().expecting(40).progress(12);
        when(ingestionService.getAllTasks()).thenReturn(List.of(task));

        List<Map<String, Object>> ingest = (List<Map<String, Object>>) service.snapshot().get("ingest");
        assertThat(ingest).hasSize(1);
        Map<String, Object> m = ingest.get(0);
        assertThat(m.get("taskId")).isEqualTo("t1");
        assertThat(m.get("status")).isEqualTo("PROCESSING"); // nom d'enum, pas l'objet
        assertThat(m.get("files")).isEqualTo(List.of("a.pdf"));
        assertThat(m.get("chunksCreated")).isEqualTo(12);
        assertThat(m.get("chunksExpected")).isEqualTo(40);
    }

    @Test
    @SuppressWarnings("unchecked")
    void trainingJobKeepsProgressFieldsButDropsHeavyOnes() {
        FineTuningJob job = new FineTuningJob("j1", FineTuningJob.Status.TRAINING, "spectra-domain",
                "phi3", null, 120, "Époque 2", 2, 3, 0.42, null, null, null,
                Instant.parse("2026-07-13T10:00:00Z"), null);
        when(fineTuningService.getAllJobs()).thenReturn(List.of(job));

        Map<String, Object> m = ((List<Map<String, Object>>) service.snapshot().get("training")).get(0);
        assertThat(m.get("modelName")).isEqualTo("spectra-domain");
        assertThat(m.get("currentEpoch")).isEqualTo(2);
        assertThat(m.get("totalEpochs")).isEqualTo(3);
        assertThat(m.get("createdAt")).isEqualTo(Instant.parse("2026-07-13T10:00:00Z"));
        // Les champs lourds / hors-suivi ne sont pas émis sur le flux SSE.
        assertThat(m).doesNotContainKeys("parameters", "loss", "outputPath", "reportPath");
    }

    @Test
    @SuppressWarnings("unchecked")
    void evaluationReportDropsScoreDetails() {
        EvaluationReport report = new EvaluationReport("e1", "RUNNING", "m1", null, 12, 3,
                0.0, Map.of(), List.of(), 0.0, 0.0, null,
                Instant.parse("2026-07-13T10:00:00Z"), null, null);
        when(evaluationService.getAllReports()).thenReturn(List.of(report));

        Map<String, Object> m = ((List<Map<String, Object>>) service.snapshot().get("evaluations")).get(0);
        assertThat(m.get("processed")).isEqualTo(3);
        assertThat(m.get("testSetSize")).isEqualTo(12);
        assertThat(m).doesNotContainKeys("scores", "scoresByCategory", "averageScore");
    }

    @Test
    @SuppressWarnings("unchecked")
    void brokenSourceYieldsEmptyListWithoutHidingOthers() {
        when(evaluationService.getAllReports()).thenThrow(new IllegalStateException("boom"));
        when(llmFitService.getInstallations()).thenReturn(List.of(
                InstallationJob.pending("i1", "phi3", "Q4_K_M", false)));

        Map<String, Object> snap = service.snapshot();
        assertThat((List<Map<String, Object>>) snap.get("evaluations")).isEmpty();
        List<Map<String, Object>> installs = (List<Map<String, Object>>) snap.get("installs");
        assertThat(installs).hasSize(1);
        assertThat(installs.get(0).get("modelName")).isEqualTo("phi3");
        assertThat(installs.get(0).get("status")).isEqualTo("PENDING");
    }

    @Test
    void identicalSnapshotsAreEqual_soDistinctUntilChangedSuppressesThem() {
        assertThat(service.snapshot()).isEqualTo(service.snapshot());
    }
}
