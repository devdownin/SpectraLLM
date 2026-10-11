package fr.spectra.mcp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpCallsControllerTest {

    @Test
    void theControllerReturnsTheMostRecentCallsAndTheCapacity() {
        McpCallJournal journal = new McpCallJournal(new SimpleMeterRegistry());
        journal.record("spectra_status", Map.of(), () -> null);
        journal.record("spectra_search", Map.of("question", "q"), () -> null);

        McpCallsController.McpCalls calls = new McpCallsController(journal).calls(1);

        assertThat(calls.capacity()).isEqualTo(McpCallJournal.CAPACITY);
        assertThat(calls.calls()).singleElement().extracting(McpCallJournal.McpCall::tool).isEqualTo("spectra_search");
    }
}
