package fr.spectra.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lie {@code spectra.*} comme Spring le fait au démarrage, à travers chaque record qui porte un
 * constructeur de commodité. Un record à deux constructeurs sans {@code @ConstructorBinding} n'a
 * pas de constructeur de liaison : Spring le laisse à {@code null}, sans erreur. En 0.12.1,
 * {@code spectra.llm.chat} n'était donc jamais lu et le client retombait sur
 * {@code http://llama-cpp-chat:8080} quelle que soit la configuration.
 */
class SpectraPropertiesBindingTest {

    private static SpectraProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("spectra", SpectraProperties.class)
                .get();
    }

    @Test
    void chatAndEmbeddingEndpointsAreBound() {
        var p = bind(Map.of(
                "spectra.llm.chat.base-url", "http://llm-chat:8081",
                "spectra.llm.chat.file", "chat.gguf",
                "spectra.llm.chat.timeout-seconds", "42",
                "spectra.llm.embedding.base-url", "http://llm-embed:8082",
                "spectra.llm.embedding.max-input-tokens", "1024"));

        assertThat(p.llm().chat()).isNotNull();
        assertThat(p.llm().chat().baseUrl()).isEqualTo("http://llm-chat:8081");
        assertThat(p.llm().chat().file()).isEqualTo("chat.gguf");
        assertThat(p.llm().chat().timeoutSeconds()).isEqualTo(42);
        assertThat(p.llm().embedding().baseUrl()).isEqualTo("http://llm-embed:8082");
        assertThat(p.llm().embedding().maxInputTokens()).isEqualTo(1024);
    }

    @Test
    void rerankerSemanticDedupAndRagSettingsAreBound() {
        var p = bind(Map.of(
                "spectra.reranker.enabled", "true",
                "spectra.reranker.min-score", "0.25",
                "spectra.semantic-dedup.strategy", "mmr",
                "spectra.semantic-dedup.mmr-lambda", "0.5",
                "spectra.rag.neighbor-window", "2",
                "spectra.rag.citation-strict", "true"));

        assertThat(p.reranker()).isNotNull();
        assertThat(p.reranker().minScore()).isEqualTo(0.25f);
        assertThat(p.semanticDedup()).isNotNull();
        assertThat(p.semanticDedup().isMmr()).isTrue();
        assertThat(p.semanticDedup().effectiveMmrLambda()).isEqualTo(0.5);
        assertThat(p.rag().effectiveNeighborWindow()).isEqualTo(2);
        assertThat(p.rag().citationStrictEnabled()).isTrue();
    }
}
