package fr.spectra.service;

import fr.spectra.model.AssistantPersona;
import fr.spectra.model.DpoPair;
import fr.spectra.model.TrainingPair;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrainingHoldoutTest {
    private static TrainingPair pair(String prompt, String source) {
        return TrainingPair.of(prompt, "answer", source, "qa", "question_answer", 0.9);
    }

    @Test
    void keepsCorrelatedSourcesAndDuplicatePromptsTogether() {
        List<TrainingPair> corpus = List.of(pair("shared", "a"), pair("another", "a"),
                pair("shared", "b"), pair("independent", "c"), pair("last", "d"));
        TrainingHoldout.Split split = TrainingHoldout.reserve(corpus, List.of(), false);
        assertThat(split.test()).isNotEmpty();
        assertThat(split.training()).isNotEmpty();
        assertThat(split.test().stream().map(p -> p.metadata().source()).toList())
                .doesNotContainAnyElementsOf(split.training().stream().map(p -> p.metadata().source()).toList());
        assertThat(split.test().stream().map(p -> p.conversations().get(1).content()).toList())
                .doesNotContainAnyElementsOf(split.training().stream().map(p -> p.conversations().get(1).content()).toList());
        assertThat(split).isEqualTo(TrainingHoldout.reserve(corpus, List.of(), false));
    }

    @Test
    void preferenceHoldoutExcludesSourcesAndPersonaPrefixedPrompts() {
        List<TrainingPair> corpus = List.of(pair("question a", "a"), pair("question b", "b"), pair("question c", "c"));
        List<DpoPair> dpo = List.of(new DpoPair(AssistantPersona.SYSTEM_PROMPT + "\nquestion a", "yes", "no", "qa", "x"),
                new DpoPair("different a", "yes", "no", "qa", "a"),
                new DpoPair("question b", "yes", "no", "qa", "b"),
                new DpoPair("question c", "yes", "no", "qa", "c"));
        TrainingHoldout.Split split = TrainingHoldout.reserve(corpus, dpo, true);
        assertThat(split.preferences()).isNotEmpty();
        for (TrainingPair test : split.test()) {
            assertThat(split.preferences()).noneMatch(p -> p.source().equals(test.metadata().source())
                    || TrainingHoldout.normalizePrompt(p.prompt()).equals(test.conversations().get(1).content()));
        }
    }

    @Test
    void refusesTinyOrFullyCorrelatedDatasets() {
        assertThatThrownBy(() -> TrainingHoldout.reserve(List.of(pair("a", "same"), pair("b", "same")), List.of(), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("groupes indépendants");
        assertThatThrownBy(() -> TrainingHoldout.reserve(List.of(), List.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
