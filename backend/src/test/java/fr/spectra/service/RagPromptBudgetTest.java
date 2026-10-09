package fr.spectra.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.OptionalInt;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagPromptBudgetTest {
    private final LlmChatClient llm = mock(LlmChatClient.class);

    @Test void oversizedFirstPassageDoesNotHideLaterEvidence() {
        when(llm.servedContextTokens()).thenReturn(OptionalInt.of(1024));
        List<String> passages = List.of("a".repeat(6000), "preuve courte");
        assertThat(RagPromptBudget.selectIndices(2, llm, Integer.MAX_VALUE,
                indices -> "instructions et question\n" + indices.stream().map(passages::get).reduce("", (a, b) -> a + b)))
                .containsExactly(1);
    }

    @Test void unknownWindowStillHasABoundedFallback() {
        when(llm.servedContextTokens()).thenReturn(OptionalInt.empty());
        assertThat(RagPromptBudget.selectIndices(1, llm, Integer.MAX_VALUE,
                indices -> "instructions" + (indices.isEmpty() ? "" : "x".repeat(20000)))).isEmpty();
    }

    @Test void tinyServedWindowFailsBeforeSendingAnImpossiblePrompt() {
        when(llm.servedContextTokens()).thenReturn(OptionalInt.of(256));
        assertThatThrownBy(() -> RagPromptBudget.selectIndices(0, llm, Integer.MAX_VALUE, indices -> "question"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
