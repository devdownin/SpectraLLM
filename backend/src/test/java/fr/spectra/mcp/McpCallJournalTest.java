package fr.spectra.mcp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpCallJournalTest {

    private SimpleMeterRegistry registry;
    private McpCallJournal journal;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        journal = new McpCallJournal(registry);
    }

    @Test
    void aSuccessfulCallIsRecordedWithItsArgumentsAndTimed() {
        assertThat(journal.record("spectra_search", Map.of("question", "q"), () -> "ok")).isEqualTo("ok");

        assertThat(journal.recent(10)).singleElement().satisfies(call -> {
            assertThat(call.tool()).isEqualTo("spectra_search");
            assertThat(call.outcome()).isEqualTo(McpCallJournal.Outcome.OK);
            assertThat(call.arguments()).containsEntry("question", "q");
            assertThat(call.detail()).isNull();
        });
        assertThat(registry.get(McpCallJournal.TIMER).tag("tool", "spectra_search").tag("outcome", "OK")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void eachFailureIsFiledUnderTheOutcomeThatSaysWhoseItIs() {
        assertThatThrownBy(() -> journal.record("t", Map.of(), () -> { throw new IllegalArgumentException("blank"); }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> journal.record("t", Map.of(), () -> McpToolFailure.guard("t",
                () -> { throw new java.util.NoSuchElementException("Document introuvable : x"); })))
                .isInstanceOf(McpToolFailure.class);
        assertThatThrownBy(() -> journal.record("t", Map.of(), () -> { throw new UnsupportedOperationException("x"); }))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(journal.recent(10)).extracting(McpCallJournal.McpCall::outcome).containsExactly(
                McpCallJournal.Outcome.FAILURE, McpCallJournal.Outcome.SERVICE_ERROR, McpCallJournal.Outcome.CALLER_ERROR);
        assertThat(journal.recent(10).get(2).detail()).isEqualTo("blank");
    }

    @Test
    void absentArgumentsAreLeftOutAndLongOnesTruncated() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("collection", null);
        arguments.put("question", "x".repeat(McpCallJournal.MAX_ARGUMENT_CHARS + 50));

        journal.record("spectra_ask", arguments, () -> null);

        Map<String, String> recorded = journal.recent(1).getFirst().arguments();
        assertThat(recorded).doesNotContainKey("collection");
        assertThat(recorded.get("question")).hasSize(McpCallJournal.MAX_ARGUMENT_CHARS + 1).endsWith("…");
    }

    @Test
    void theJournalKeepsTheMostRecentCallsUpToItsCapacity() {
        for (int i = 0; i < McpCallJournal.CAPACITY + 5; i++) {
            int n = i;
            journal.record("t", Map.of("n", n), () -> null);
        }

        assertThat(journal.recent(Integer.MAX_VALUE)).hasSize(McpCallJournal.CAPACITY);
        assertThat(journal.recent(1).getFirst().arguments()).containsEntry("n", String.valueOf(McpCallJournal.CAPACITY + 4));
    }
}
