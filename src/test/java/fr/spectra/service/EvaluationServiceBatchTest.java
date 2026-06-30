package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.spectra.dto.EvaluationReport;
import fr.spectra.model.TrainingPair;
import fr.spectra.persistence.DocumentModelLinkRepository;
import fr.spectra.service.dataset.DatasetGeneratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests de l'évaluation par lot (modèle ciblé + jeu de test partagé + restauration)
 * et de la rétention des rapports.
 */
class EvaluationServiceBatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @TempDir
    Path tempDir;

    private DatasetGeneratorService datasetGenerator;
    private LlmChatClient chatClient;
    private AtomicReference<String> activeModel;

    @BeforeEach
    void setUp() {
        datasetGenerator = mock(DatasetGeneratorService.class);
        chatClient = mock(LlmChatClient.class);
        activeModel = new AtomicReference<>("orig");
        when(chatClient.getActiveModel()).thenAnswer(i -> activeModel.get());
        doAnswer(i -> { activeModel.set(i.getArgument(0)); return null; })
                .when(chatClient).setActiveModel(anyString());
        // Réponse du modèle ET du juge : JSON parseable → score 8.
        when(chatClient.chat(anyString(), anyString()))
                .thenReturn("{\"score\": 8, \"justification\": \"ok\"}");
    }

    @Test
    void batchEvaluatesEachModelOnSharedTestSetAndRestoresActiveModel() {
        List<TrainingPair> pairs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            pairs.add(TrainingPair.of("q" + i, "a" + i, "doc.pdf", "qa", "qa", 1.0));
        }
        when(datasetGenerator.getAllPairs()).thenReturn(pairs);

        EvaluationService service = new EvaluationService(
                datasetGenerator, chatClient, mock(DocumentModelLinkRepository.class),
                tempDir.toString(), 200, "");
        service.init();

        List<String> evalIds = service.submitBatch(List.of("model-a", "model-b"), 5);

        assertThat(evalIds).hasSize(2);
        EvaluationReport a = service.getReport(evalIds.get(0));
        EvaluationReport b = service.getReport(evalIds.get(1));

        assertThat(a.status()).isEqualTo("COMPLETED");
        assertThat(b.status()).isEqualTo("COMPLETED");
        assertThat(a.modelName()).isEqualTo("model-a");
        assertThat(b.modelName()).isEqualTo("model-b");
        // Même jeu de test (taille demandée) pour les deux → comparaison équitable.
        assertThat(a.testSetSize()).isEqualTo(5);
        assertThat(b.testSetSize()).isEqualTo(5);
        assertThat(a.averageScore()).isEqualTo(8.0);
        assertThat(b.averageScore()).isEqualTo(8.0);

        // Bascule séquentielle puis restauration du modèle initial.
        InOrder order = inOrder(chatClient);
        order.verify(chatClient).setActiveModel("model-a");
        order.verify(chatClient).setActiveModel("model-b");
        order.verify(chatClient).setActiveModel("orig");
        assertThat(activeModel.get()).isEqualTo("orig");
    }

    @Test
    void neutralJudgeRunsTwoPhasesAndRestoresEvaluatedModel() {
        List<TrainingPair> pairs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            pairs.add(TrainingPair.of("q" + i, "a" + i, "doc.pdf", "qa", "qa", 1.0));
        }
        when(datasetGenerator.getAllPairs()).thenReturn(pairs);

        EvaluationService service = new EvaluationService(
                datasetGenerator, chatClient, mock(DocumentModelLinkRepository.class),
                tempDir.toString(), 200, "judge-x");   // juge neutre configuré
        service.init();

        String evalId = service.submit(new fr.spectra.dto.EvaluationRequest("model-a", 4, null));

        EvaluationReport report = service.getReport(evalId);
        assertThat(report.status()).isEqualTo("COMPLETED");
        assertThat(report.modelName()).isEqualTo("model-a");
        assertThat(report.averageScore()).isEqualTo(8.0);

        // Modèle évalué activé, puis bascule vers le juge neutre, puis restauration.
        InOrder order = inOrder(chatClient);
        order.verify(chatClient).setActiveModel("model-a");
        order.verify(chatClient).setActiveModel("judge-x");
        order.verify(chatClient).setActiveModel("orig");
        assertThat(activeModel.get()).isEqualTo("orig");
    }

    @Test
    void retentionKeepsCompletedAndPurgesOldFailures() throws Exception {
        // Aucune paire nécessaire pour ce test — on amorce des rapports persistés.
        Instant old = Instant.now().minusSeconds(7200);   // avant le cutoff (1 h)
        Instant recent = Instant.now();
        Map<String, EvaluationReport> seeded = Map.of(
                "c1", completed("c1", recent.minusSeconds(30)),
                "c2", completed("c2", recent.minusSeconds(20)),
                "c3", completed("c3", recent.minusSeconds(10)),
                "f-old", failed("f-old", old),
                "f-recent", failed("f-recent", recent)
        );
        Files.createDirectories(tempDir);
        MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(tempDir.resolve("evaluations.json").toFile(), seeded);

        when(datasetGenerator.getAllPairs()).thenReturn(List.of());
        EvaluationService service = new EvaluationService(
                datasetGenerator, chatClient, mock(DocumentModelLinkRepository.class),
                tempDir.toString(), 2, "");   // cap COMPLETED à 2
        service.init();

        service.cleanupOldReports();

        List<String> ids = service.getAllReports().stream().map(EvaluationReport::evalId).toList();
        // L'échec ancien est purgé, l'échec récent conservé.
        assertThat(ids).contains("f-recent").doesNotContain("f-old");
        // Cap COMPLETED=2 → seuls les 2 plus récents (c2, c3) subsistent.
        long completedKept = service.getAllReports().stream()
                .filter(r -> "COMPLETED".equals(r.status())).count();
        assertThat(completedKept).isEqualTo(2L);
        assertThat(ids).contains("c2", "c3").doesNotContain("c1");
    }

    private static EvaluationReport completed(String id, Instant completedAt) {
        return new EvaluationReport(id, "COMPLETED", "m-" + id, null,
                5, 5, 8.0, Map.of("qa", 8.0), List.of(), 100.0, 20.0, null,
                completedAt.minusSeconds(60), completedAt);
    }

    private static EvaluationReport failed(String id, Instant completedAt) {
        return new EvaluationReport(id, "FAILED", "m-" + id, null,
                0, 0, 0.0, Map.of(), List.of(), 0.0, 0.0, "boom",
                completedAt.minusSeconds(60), completedAt);
    }
}
