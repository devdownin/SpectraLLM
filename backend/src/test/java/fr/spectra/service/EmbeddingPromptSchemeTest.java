package fr.spectra.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingPromptSchemeTest {

    @ParameterizedTest
    @CsvSource({
            "nomic-embed-text, NOMIC",
            "nomic-embed-text-v1.5.Q4_K_M.gguf, NOMIC",
            "intfloat/multilingual-e5-small, E5",
            "e5-large-v2, E5",
            "Qwen3-Embedding-0.6B-Q8_0.gguf, QWEN3",
            "bge-small-en-v1.5, REPRESENT",
            "mxbai-embed-large, REPRESENT",
            "snowflake-arctic-embed-m, REPRESENT",
            "bge-m3, NONE",
            "qwen2.5-7b-instruct, NONE",
            "all-minilm, NONE"
    })
    void detectsSchemeFromModelName(String model, EmbeddingPromptScheme expected) {
        assertThat(EmbeddingPromptScheme.detect(model)).isEqualTo(expected);
    }

    @Test
    void unknownOrMissingModelHasNoPrefix() {
        assertThat(EmbeddingPromptScheme.detect(null)).isEqualTo(EmbeddingPromptScheme.NONE);
        assertThat(EmbeddingPromptScheme.detect(" ")).isEqualTo(EmbeddingPromptScheme.NONE);
    }

    @Test
    void prefixesQueriesAndDocumentsSeparately() {
        assertThat(EmbeddingPromptScheme.NOMIC.query("q")).isEqualTo("search_query: q");
        assertThat(EmbeddingPromptScheme.NOMIC.documents(List.of("a", "b")))
                .containsExactly("search_document: a", "search_document: b");
        assertThat(EmbeddingPromptScheme.QWEN3.documents(List.of("a"))).containsExactly("a");
    }

    @Test
    void noneLeavesTextsUntouched() {
        List<String> texts = List.of("a");
        assertThat(EmbeddingPromptScheme.NONE.query("q")).isEqualTo("q");
        assertThat(EmbeddingPromptScheme.NONE.documents(texts)).isSameAs(texts);
    }

    @Test
    void absentOrUnknownStampMeansLegacyIndexingWithoutPrefix() {
        assertThat(EmbeddingPromptScheme.fromStamp(null)).isEqualTo(EmbeddingPromptScheme.NONE);
        assertThat(EmbeddingPromptScheme.fromStamp("FUTURE_SCHEME")).isEqualTo(EmbeddingPromptScheme.NONE);
        assertThat(EmbeddingPromptScheme.fromStamp("nomic")).isEqualTo(EmbeddingPromptScheme.NOMIC);
    }
}
