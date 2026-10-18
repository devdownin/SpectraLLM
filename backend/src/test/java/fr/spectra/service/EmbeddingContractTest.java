package fr.spectra.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingContractTest {

    @Test
    void nomicWindowIsTheLlamaCppContextOfTheOfficialGguf() {
        EmbeddingContract contract = EmbeddingContract.current("nomic-embed-text", 8192);

        assertThat(contract.prompts()).isEqualTo(EmbeddingPromptScheme.NOMIC);
        assertThat(contract.maxInputTokens()).isEqualTo(2048);
        assertThat(contract.textVersion()).isEqualTo(EmbeddingContract.TEXT_VERSION);
    }

    @Test
    void servedWindowBoundsTheModelLimit() {
        assertThat(EmbeddingContract.current("nomic-embed-text", 1024).maxInputTokens()).isEqualTo(1024);
        assertThat(EmbeddingContract.current("multilingual-e5-base", 2048).maxInputTokens()).isEqualTo(512);
        assertThat(EmbeddingContract.current("modele-inconnu", 2048).maxInputTokens()).isEqualTo(2048);
    }

    @Test
    void documentTextIsTaskPrefixThenHeaderThenText() {
        EmbeddingContract contract = EmbeddingContract.current("nomic-embed-text", 2048);

        assertThat(contract.documentText("Texte.", Map.of("sourceFile", "a.pdf"), true))
                .isEqualTo("search_document: Document : a.pdf\n\nTexte.");
        assertThat(contract.documentText("Texte.", Map.of("sourceFile", "a.pdf"), false))
                .isEqualTo("search_document: Texte.");
    }

    @Test
    void olderPromptsOrTextVersionCallForReindexing() {
        assertThat(new EmbeddingContract("nomic-embed-text", EmbeddingPromptScheme.NONE, 2, 2048)
                .isCurrentFor("nomic-embed-text")).isFalse();
        assertThat(new EmbeddingContract("nomic-embed-text", EmbeddingPromptScheme.NOMIC, 1, 2048)
                .isCurrentFor("nomic-embed-text")).isFalse();
        assertThat(EmbeddingContract.current("nomic-embed-text", 2048).isCurrentFor("nomic-embed-text")).isTrue();
        assertThat(EmbeddingContract.current("nomic-embed-text", 2048).label()).isEqualTo("NOMIC/v2");
    }
}
