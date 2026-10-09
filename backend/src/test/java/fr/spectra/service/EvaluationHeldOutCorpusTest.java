package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.dto.EvaluationRequest;
import fr.spectra.model.TrainingPair;
import fr.spectra.persistence.DocumentModelLinkRepository;
import fr.spectra.service.dataset.DatasetGeneratorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class EvaluationHeldOutCorpusTest {
    @TempDir Path workDir;

    @Test
    void loadsJobSnapshotWithoutConsultingCurrentDataset() throws Exception {
        DatasetGeneratorService generator = mock(DatasetGeneratorService.class);
        EvaluationService service = service(generator);
        TrainingPair heldOut = TrainingPair.of("unseen", "reference", "held-out.pdf", "qa", "qa", 0.9);
        Files.createDirectories(workDir.resolve("job-a"));
        Files.writeString(workDir.resolve("job-a/evaluation.jsonl"), new ObjectMapper().writeValueAsString(heldOut) + "\n");
        assertThat(service.loadHeldOutPairs("job-a")).isEqualTo(List.of(heldOut));
        verifyNoInteractions(generator);
    }

    @Test
    void missingSnapshotRejectsLinkedEvaluationInsteadOfFallingBack() {
        DatasetGeneratorService generator = mock(DatasetGeneratorService.class);
        EvaluationService service = service(generator);
        assertThatThrownBy(() -> service.submit(new EvaluationRequest("model", null, "old-job")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("réservé indisponible");
        verifyNoInteractions(generator);
        assertThatThrownBy(() -> service.loadHeldOutPairs("../escape"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private EvaluationService service(DatasetGeneratorService generator) {
        return new EvaluationService(generator, mock(LlmChatClient.class),
                mock(LlmJudge.class), mock(ModelSwitchCoordinator.class),
                mock(DocumentModelLinkRepository.class), workDir.toString(), 200, "");
    }
}
