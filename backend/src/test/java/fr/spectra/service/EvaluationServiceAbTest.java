package fr.spectra.service;

import fr.spectra.dto.AbComparisonReport;
import fr.spectra.model.TrainingPair;
import fr.spectra.persistence.DocumentModelLinkRepository;
import fr.spectra.service.dataset.DatasetGeneratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Tests de la comparaison directe A/B (head-to-head) : génération en deux phases,
 * bascule des modèles puis du juge, agrégation des verdicts.
 */
class EvaluationServiceAbTest {

    @TempDir
    Path tempDir;

    private DatasetGeneratorService datasetGenerator;
    private LlmChatClient chatClient;
    private ModelSwitchCoordinator modelSwitch;
    private AtomicReference<String> activeModel;

    @BeforeEach
    void setUp() {
        datasetGenerator = mock(DatasetGeneratorService.class);
        chatClient = mock(LlmChatClient.class);
        activeModel = new AtomicReference<>("orig");
        when(chatClient.getActiveModel()).thenAnswer(i -> activeModel.get());
        doAnswer(i -> { activeModel.set(i.getArgument(0)); return null; })
                .when(chatClient).setActiveModel(anyString());
        // Serveur "convergé" immédiatement : l'attente de convergence du coordinateur passe.
        when(chatClient.checkHealth()).thenReturn(new fr.spectra.dto.ServiceStatus(
                "llama-cpp", "http://test", true, "ok", 1, java.util.Map.of()));
        modelSwitch = new ModelSwitchCoordinator(chatClient, 2, 1);
        // Génération : réponse quelconque. Jugement A/B (prompt contenant "Réponse 1") : gagnant 1.
        when(chatClient.chat(anyString(), anyString())).thenReturn("réponse du modèle");
        when(chatClient.chat(argThat(s -> s != null && s.contains("Réponse 1")), anyString()))
                .thenReturn("{\"winner\": 1, \"justification\": \"meilleure\"}");
        // chatWithStats (méthode default) déléguée à chat() : la génération l'utilise.
        when(chatClient.chatWithStats(anyString(), anyString()))
                .thenAnswer(i -> new LlmChatClient.ChatResult(
                        chatClient.chat(i.getArgument(0), i.getArgument(1)), 0, 0.0));
    }

    private EvaluationService newService() {
        EvaluationService service = new EvaluationService(
                datasetGenerator, chatClient, modelSwitch, mock(DocumentModelLinkRepository.class),
                tempDir.toString(), 200, "judge-x");
        service.init();
        return service;
    }

    @Test
    void runsHeadToHeadAndAggregatesVerdicts() {
        List<TrainingPair> pairs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            pairs.add(TrainingPair.of("q" + i, "a" + i, "doc.pdf", "qa", "qa", 1.0));
        }
        when(datasetGenerator.getAllPairs()).thenReturn(pairs);

        EvaluationService service = newService();
        String abId = service.submitAb("model-a", "model-b", 6);

        AbComparisonReport report = service.getAbReport(abId);
        assertThat(report.status()).isEqualTo("COMPLETED");
        assertThat(report.modelA()).isEqualTo("model-a");
        assertThat(report.modelB()).isEqualTo("model-b");
        assertThat(report.judgeModel()).isEqualTo("judge-x");
        assertThat(report.processed()).isEqualTo(6);
        // Juge renvoie toujours "Réponse 1" → aucune égalité ; toutes les paires tranchées.
        assertThat(report.ties()).isZero();
        assertThat(report.aWins() + report.bWins()).isEqualTo(6);
        assertThat(report.items()).hasSize(6);
        assertThat(report.winRateA() + report.winRateB()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.05));

        // Phases : A puis B (génération), puis juge, puis restauration du modèle initial.
        InOrder order = inOrder(chatClient);
        order.verify(chatClient).setActiveModel("model-a");
        order.verify(chatClient).setActiveModel("model-b");
        order.verify(chatClient).setActiveModel("judge-x");
        order.verify(chatClient).setActiveModel("orig");
        assertThat(activeModel.get()).isEqualTo("orig");
    }

    @Test
    void rejectsIdenticalModels() {
        EvaluationService service = newService();
        assertThatThrownBy(() -> service.submitAb("m", "m", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingModel() {
        EvaluationService service = newService();
        assertThatThrownBy(() -> service.submitAb("m", "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
