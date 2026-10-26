package fr.spectra.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie la sémantique tri-état de {@link RagOverrides#resolve} : null = défaut,
 * true = activé seulement si disponible, false = toujours désactivé.
 */
class RagOverridesTest {

    @Test
    void nullOverride_usesDeploymentDefault() {
        assertThat(RagOverrides.resolve(null, true)).isTrue();
        assertThat(RagOverrides.resolve(null, false)).isFalse();
    }

    @Test
    void trueOverride_onlyEffectiveWhenModulePresent() {
        assertThat(RagOverrides.resolve(true, true)).isTrue();
        assertThat(RagOverrides.resolve(true, false)).isFalse(); // ne peut activer un module absent
    }

    @Test
    void falseOverride_alwaysDisables() {
        assertThat(RagOverrides.resolve(false, true)).isFalse();
        assertThat(RagOverrides.resolve(false, false)).isFalse();
    }

    @Test
    void none_hasAllFieldsNull() {
        RagOverrides n = RagOverrides.NONE;
        assertThat(n.rerank()).isNull();
        assertThat(n.hybrid()).isNull();
        assertThat(n.multiQuery()).isNull();
        assertThat(n.corrective()).isNull();
        assertThat(n.compression()).isNull();
        assertThat(n.selfRag()).isNull();
        assertThat(n.adaptive()).isNull();
        assertThat(n.conversational()).isNull();
    }
    @Test
    void neighborWindowIsValidatedIncludingNestedRequest() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (int value : new int[]{-1, 6, Integer.MAX_VALUE}) {
                var overrides = RagOverrides.NONE.withTuning(null, value, null);
                var request = new QueryRequest("question", 5, 20, null, null, null,
                        null, true, overrides);
                assertThat(validator.validate(request)).anyMatch(v ->
                        v.getPropertyPath().toString().equals("overrides.neighborWindow"));
            }
            for (int value : new int[]{0, 5}) {
                assertThat(validator.validate(RagOverrides.NONE.withTuning(null, value, null))).isEmpty();
            }
        }
    }
}
