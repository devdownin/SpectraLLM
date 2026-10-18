package fr.spectra.service;

import fr.spectra.model.TextChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingInputGuardTest {

    /** Tokenizer factice : un token par mot, plus [CLS]/[SEP] — plus fin que cl100k, comme WordPiece sur du français. */
    private static Optional<List<Integer>> wordCount(List<String> texts) {
        return Optional.of(texts.stream().map(t -> t.isBlank() ? 2 : t.strip().split("\\s+").length + 2).toList());
    }

    private EmbeddingService embeddingService;
    private EmbeddingInputGuard guard;
    private final EmbeddingContract nomic100 = new EmbeddingContract(
            "nomic-embed-text", EmbeddingPromptScheme.NOMIC, EmbeddingContract.TEXT_VERSION, 100);

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        when(embeddingService.countTokens(anyList())).thenAnswer(inv -> wordCount(inv.getArgument(0)));
        guard = new EmbeddingInputGuard(embeddingService, true);
    }

    private static TextChunk chunk(int index, String text) {
        return new TextChunk("id-" + index, text, index, "doc.txt",
                Map.of("sourceFile", "doc.txt", "chunkIndex", String.valueOf(index)));
    }

    private static String words(int n, String word) {
        return (word + " ").repeat(n).strip();
    }

    @Test
    void shortChunksAreKeptWithoutCallingTheTokenizer() {
        List<TextChunk> chunks = List.of(chunk(0, "Court."));

        assertThat(guard.fit(chunks, nomic100)).isSameAs(chunks);
        verify(embeddingService, never()).countTokens(anyList());
    }

    @Test
    void oversizedChunkIsSplitBySentencesAndTheDocumentIsRenumbered() {
        String sentence = words(30, "mot") + ".";
        List<TextChunk> chunks = List.of(chunk(0, "Avant."), chunk(1, String.join(" ", sentence, sentence, sentence, sentence)),
                chunk(2, "Après."));

        List<TextChunk> fitted = guard.fit(chunks, nomic100);

        assertThat(fitted.size()).isGreaterThan(3);
        assertThat(wordCount(guard.documentTexts(fitted, nomic100)).orElseThrow())
                .allSatisfy(tokens -> assertThat(tokens).isLessThanOrEqualTo(100));
        assertThat(fitted).extracting(TextChunk::index).containsExactly(
                java.util.stream.IntStream.range(0, fitted.size()).boxed().toArray(Integer[]::new));
        assertThat(fitted).allSatisfy(c -> assertThat(c.metadata()).containsEntry("chunkIndex", String.valueOf(c.index())));
        // Les chunks qui tenaient gardent leur identité ; les morceaux en reçoivent une neuve.
        assertThat(fitted.getFirst().id()).isEqualTo("id-0");
        assertThat(fitted.getLast().id()).isEqualTo("id-2");
        assertThat(String.join(" ", fitted.subList(1, fitted.size() - 1).stream().map(TextChunk::text).toList()))
                .isEqualTo(chunks.get(1).text());
    }

    @Test
    void sentenceLongerThanTheWindowIsCutAtWhitespace() {
        List<TextChunk> fitted = guard.fit(List.of(chunk(0, words(400, "mot"))), nomic100);

        assertThat(fitted.size()).isGreaterThan(1);
        assertThat(wordCount(guard.documentTexts(fitted, nomic100)).orElseThrow())
                .allSatisfy(tokens -> assertThat(tokens).isLessThanOrEqualTo(100));
    }

    @Test
    void unavailableTokenizerLeavesChunksUntouched() {
        when(embeddingService.countTokens(anyList())).thenReturn(Optional.empty());
        List<TextChunk> chunks = List.of(chunk(0, words(400, "mot")));

        assertThat(guard.fit(chunks, nomic100)).isSameAs(chunks);
    }

    @Test
    void embeddedTextCarriesThePrefixBeforeTheHeader() {
        assertThat(guard.documentTexts(List.of(chunk(0, "Texte.")), nomic100))
                .containsExactly("search_document: Document : doc.txt\n\nTexte.");
    }

    @Test
    void reindexTruncatesInsteadOfSplitting() {
        List<String> truncated = guard.truncateToFit(List.of(words(400, "mot"), "court"), nomic100);

        assertThat(wordCount(List.of(truncated.getFirst())).orElseThrow().getFirst()).isLessThanOrEqualTo(100);
        assertThat(truncated.get(1)).isEqualTo("court");
    }

    @Test
    void longQueryIsPrefixedThenTruncated() {
        String query = guard.queryText(words(400, "mot"), nomic100);

        assertThat(query).startsWith("search_query: mot");
        assertThat(wordCount(List.of(query)).orElseThrow().getFirst()).isLessThanOrEqualTo(100);
    }
}
