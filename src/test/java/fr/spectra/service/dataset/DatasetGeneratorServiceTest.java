package fr.spectra.service.dataset;

import fr.spectra.service.ChromaDbClient;
import fr.spectra.service.LlmChatClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.nio.file.Path;

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
        service = new DatasetGeneratorService(mock(LlmChatClient.class), mock(ChromaDbClient.class));

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
        String taskId = service.submit();
        assertThat(taskId).isNotBlank();
        DatasetGeneratorService.GenerationTask task = service.getTask(taskId);
        assertThat(task).isNotNull();
        assertThat(task.taskId()).isEqualTo(taskId);
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private String invoke(String input) throws Exception {
        return (String) extractJsonMethod.invoke(service, input);
    }
}
