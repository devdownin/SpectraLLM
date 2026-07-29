package fr.spectra.service.dataset;

import fr.spectra.dto.DatasetStats;
import fr.spectra.model.TrainingPair;
import fr.spectra.service.ChromaDbClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Statistiques du dataset — en particulier la répartition par catégorie de document (R8),
 * qui sert à repérer un thème sur- ou sous-représenté avant de lancer un fine-tuning.
 */
class DatasetExportServiceTest {

    @TempDir
    Path tempDir;

    private DatasetGeneratorService generator;
    private DatasetExportService service;

    @BeforeEach
    void setUp() {
        generator = mock(DatasetGeneratorService.class);
        ChromaDbClient chroma = mock(ChromaDbClient.class);
        when(chroma.getOrCreateCollection(anyString())).thenReturn("col-id");
        when(chroma.count("col-id")).thenReturn(42);
        service = new DatasetExportService(generator, chroma, tempDir.toString());
    }

    private static TrainingPair pair(String category, String type, String documentCategory) {
        return TrainingPair.of("q", "r", "doc.pdf", category, type, 0.8, documentCategory);
    }

    @Test
    void stats_breaksDownPairsByDocumentCategory() {
        when(generator.getAllPairs()).thenReturn(List.of(
                pair("qa", "question_answer", "maintenance"),
                pair("summary", "summarization", "maintenance"),
                pair("qa", "question_answer", "reglementation")));

        DatasetStats stats = service.getStats();

        assertThat(stats.byDocumentCategory())
                .containsEntry("maintenance", 2)
                .containsEntry("reglementation", 1);
    }

    @Test
    void stats_documentCategoryIsIndependentFromPairCategory() {
        // Les deux ventilations sont orthogonales : « de quel thème vient la paire »
        // n'a rien à voir avec « quel exercice elle enseigne ».
        when(generator.getAllPairs()).thenReturn(List.of(
                pair("qa", "question_answer", "securite"),
                pair("negative", "refusal", "securite")));

        DatasetStats stats = service.getStats();

        assertThat(stats.byCategory()).containsEntry("qa", 1).containsEntry("negative", 1);
        assertThat(stats.byDocumentCategory()).containsEntry("securite", 2);
    }

    @Test
    void stats_legacyPairsWithoutDocumentCategoryFallIntoTheUnclassifiedBucket() {
        // Les paires générées avant la classification n'ont pas de catégorie documentaire.
        // Elles sont regroupées et non ignorées, sinon le total de cette ventilation ne
        // correspondrait plus au nombre de paires.
        when(generator.getAllPairs()).thenReturn(List.of(
                pair("qa", "question_answer", null),
                pair("qa", "question_answer", "maintenance")));

        DatasetStats stats = service.getStats();

        assertThat(stats.byDocumentCategory())
                .containsEntry(DatasetGeneratorService.UNCLASSIFIED_BUCKET, 1)
                .containsEntry("maintenance", 1);
        assertThat(stats.byDocumentCategory().values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(stats.totalPairs());
    }

    @Test
    void stats_emptyDataset_yieldsEmptyBreakdownsAndZeroConfidence() {
        when(generator.getAllPairs()).thenReturn(List.of());

        DatasetStats stats = service.getStats();

        assertThat(stats.totalPairs()).isZero();
        assertThat(stats.byDocumentCategory()).isEmpty();
        assertThat(stats.avgConfidence()).isZero();
        assertThat(stats.chunksInStore()).isEqualTo(42);
    }

    @Test
    void stats_chromaUnavailable_stillReportsPairBreakdowns() {
        ChromaDbClient failing = mock(ChromaDbClient.class);
        when(failing.getOrCreateCollection(anyString()))
                .thenThrow(new RuntimeException("ChromaDB down"));
        when(generator.getAllPairs()).thenReturn(List.of(pair("qa", "question_answer", "technique")));

        DatasetStats stats =
                new DatasetExportService(generator, failing, tempDir.toString()).getStats();

        // Le comptage de chunks est best-effort : son échec ne doit pas priver l'UI des
        // statistiques du dataset, qui ne dépendent pas de ChromaDB.
        assertThat(stats.chunksInStore()).isZero();
        assertThat(stats.byDocumentCategory()).containsEntry("technique", 1);
    }
}
