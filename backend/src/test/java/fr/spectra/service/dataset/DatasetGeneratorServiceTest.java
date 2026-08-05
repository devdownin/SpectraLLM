package fr.spectra.service.dataset;

import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.service.ChromaDbClient;
import fr.spectra.service.LlmChatClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests unitaires pour DatasetGeneratorService.
 * Focus sur l'extraction JSON (extractJson) et la gestion des tâches.
 */
class DatasetGeneratorServiceTest {

    @TempDir
    Path tempDir;

    private DatasetGeneratorService service;
    private Method extractJsonMethod;

    @BeforeEach
    void setUp() throws Exception {
        service = new DatasetGeneratorService(mock(LlmChatClient.class), mock(ChromaDbClient.class),
                mock(IngestedFileRepository.class), null, "./data/dataset", 3, 2, 2);

        extractJsonMethod = DatasetGeneratorService.class.getDeclaredMethod("extractJson", String.class);
        extractJsonMethod.setAccessible(true);
    }

    // ── extractJson ──────────────────────────────────────────────────────────

    @Test
    void extractJson_returnsEmptyObjectOnNull() throws Exception {
        String result = invoke(null);
        assertThat(result).isEqualTo("{}");
    }

    @Test
    void extractJson_returnsEmptyObjectOnBlank() throws Exception {
        assertThat(invoke("   ")).isEqualTo("{}");
    }

    @Test
    void extractJson_extractsPlainJson() throws Exception {
        String input = "{\"question\": \"Quelle est la vitesse ?\", \"answer\": \"120 km/h\"}";
        assertThat(invoke(input)).isEqualTo(input);
    }

    @Test
    void extractJson_stripsMarkdownCodeBlock() throws Exception {
        String input = "```json\n{\"question\": \"Q?\", \"answer\": \"R.\"}\n```";
        assertThat(invoke(input)).isEqualTo("{\"question\": \"Q?\", \"answer\": \"R.\"}");
    }

    @Test
    void extractJson_stripsMarkdownBlockWithoutLanguage() throws Exception {
        String input = "```\n{\"key\": \"value\"}\n```";
        assertThat(invoke(input)).isEqualTo("{\"key\": \"value\"}");
    }

    @Test
    void extractJson_handlesTextBeforeAndAfterJson() throws Exception {
        String input = "Voici ma réponse : {\"category\": \"procedures\"} merci.";
        assertThat(invoke(input)).isEqualTo("{\"category\": \"procedures\"}");
    }

    @Test
    void extractJson_handlesNestedObjects() throws Exception {
        String input = "{\"outer\": {\"inner\": \"value\"}, \"key\": \"val\"}";
        assertThat(invoke(input)).isEqualTo(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "```json\n{\"a\": \"b\"}\n```",
        "```JSON\n{\"a\": \"b\"}\n```",
        "Réponse:\n```json\n{\"a\": \"b\"}\n```\nFin.",
        "{\"a\": \"b\"}"
    })
    void extractJson_alwaysReturnsValidJsonObject(String input) throws Exception {
        String result = invoke(input);
        assertThat(result).startsWith("{");
        assertThat(result).endsWith("}");
    }

    // ── state ─────────────────────────────────────────────────────────────────

    @Test
    void getAllPairs_returnsEmptyListInitially() {
        assertThat(service.getAllPairs()).isEmpty();
    }

    @Test
    void getTask_returnsNullForUnknownTaskId() {
        assertThat(service.getTask("unknown-id")).isNull();
    }

    @Test
    void submit_createsTaskWithPendingStatus() {
        String taskId = service.submit(0);
        assertThat(taskId).isNotBlank();
        DatasetGeneratorService.GenerationTask task = service.getTask(taskId);
        assertThat(task).isNotNull();
        assertThat(task.taskId()).isEqualTo(taskId);
    }

    // ── R8 — Échantillonnage stratifié par catégorie ─────────────────────────

    /** Un document distinct par chunk : neutralise le 2e niveau pour isoler le 1er. */
    private static List<String> distinctDocuments(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> "doc" + i).toList();
    }

    @Test
    void selectStratified_underBudget_keepsEveryChunk() {
        List<String> categories = List.of("a", "b", "a");

        assertThat(DatasetGeneratorService.selectStratified(categories, distinctDocuments(3), 10))
                .containsExactly(0, 1, 2);
    }

    @Test
    void selectStratified_spreadsAcrossCategoriesInsteadOfTakingTheFirstOnes() {
        // 10 chunks « procedures » puis 2 « reglementation » : le troncage historique
        // (les N premiers) n'aurait rendu QUE des procedures.
        List<String> categories = new java.util.ArrayList<>(Collections.nCopies(10, "procedures"));
        categories.addAll(List.of("reglementation", "reglementation"));

        List<Integer> selected =
                DatasetGeneratorService.selectStratified(categories, distinctDocuments(12), 4);

        assertThat(selected).hasSize(4);
        assertThat(selected.stream().map(categories::get).toList()).containsExactlyInAnyOrder(
                "procedures", "procedures", "reglementation", "reglementation");
    }

    @Test
    void selectStratified_exhaustedCategoryYieldsItsQuotaToTheOthers() {
        // Une seule « reglementation » : le budget de 5 doit malgré tout être atteint.
        List<String> categories = new java.util.ArrayList<>(Collections.nCopies(10, "procedures"));
        categories.add("reglementation");

        List<Integer> selected =
                DatasetGeneratorService.selectStratified(categories, distinctDocuments(11), 5);

        assertThat(selected).hasSize(5);
        assertThat(selected.stream().map(categories::get).filter("reglementation"::equals))
                .hasSize(1);
    }

    @Test
    void selectStratified_spreadsAcrossDocumentsWithinACategory() {
        // Même catégorie partout, mais un premier document énorme : sans le 2e niveau de
        // stratification, tout le budget serait puisé dans « gros » et les deux autres
        // documents n'apparaîtraient jamais dans le dataset.
        List<String> categories = Collections.nCopies(12, "maintenance");
        List<String> documents = new java.util.ArrayList<>(Collections.nCopies(10, "gros"));
        documents.add("petit-a");
        documents.add("petit-b");

        List<Integer> selected = DatasetGeneratorService.selectStratified(categories, documents, 3);

        assertThat(selected.stream().map(documents::get).toList())
                .containsExactlyInAnyOrder("gros", "petit-a", "petit-b");
    }

    @Test
    void selectStratified_returnsIndicesSortedSoCollectionOrderIsPreserved() {
        List<String> categories = List.of("a", "b", "a", "b", "a", "b");

        List<Integer> selected =
                DatasetGeneratorService.selectStratified(categories, distinctDocuments(6), 4);

        assertThat(selected).isSorted();
    }

    @Test
    void selectStratified_singleCategorySingleDocument_behavesLikePlainTruncation() {
        List<String> categories = Collections.nCopies(6, DatasetGeneratorService.UNCLASSIFIED_BUCKET);
        List<String> documents = Collections.nCopies(6, "doc.pdf");

        assertThat(DatasetGeneratorService.selectStratified(categories, documents, 3))
                .containsExactly(0, 1, 2);
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private String invoke(String input) throws Exception {
        return (String) extractJsonMethod.invoke(service, input);
    }
}
