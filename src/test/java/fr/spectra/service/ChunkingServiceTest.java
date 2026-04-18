package fr.spectra.service;

import fr.spectra.model.TextChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkingServiceTest {

    private ChunkingService chunkingService;

    @BeforeEach
    void setUp() {
        chunkingService = new ChunkingService();
    }

    @Test
    void chunk_returnsEmptyOnNullText() {
        assertThat(chunkingService.chunk(null, "file.txt", Map.of())).isEmpty();
    }

    @Test
    void chunk_returnsEmptyOnBlankText() {
        assertThat(chunkingService.chunk("   ", "file.txt", Map.of())).isEmpty();
    }

    @Test
    void chunk_singleShortParagraph() {
        String text = "Un paragraphe court.";
        List<TextChunk> chunks = chunkingService.chunk(text, "test.txt", Map.of());
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo(text);
        assertThat(chunks.get(0).sourceFile()).isEqualTo("test.txt");
    }

    @Test
    void chunk_assignsSourceFileToMetadata() {
        List<TextChunk> chunks = chunkingService.chunk("Du texte.", "mon_fichier.pdf", Map.of());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).sourceFile()).isEqualTo("mon_fichier.pdf");
    }

    @Test
    void chunk_assignsChunkIndex() {
        String text = "Paragraphe A.\n\nParagraphe B.\n\nParagraphe C.";
        List<TextChunk> chunks = chunkingService.chunk(text, "file.txt", Map.of());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).index()).isEqualTo(0);
    }

    @Test
    void chunk_generatesUniqueIds() {
        String text = "Paragraphe A.\n\nParagraphe B.\n\nParagraphe C.";
        List<TextChunk> chunks = chunkingService.chunk(text, "file.txt", Map.of());
        long uniqueIds = chunks.stream().map(TextChunk::id).distinct().count();
        assertThat(uniqueIds).isEqualTo(chunks.size());
    }

    @Test
    void chunk_splitsLargeText() {
        // Créer un texte qui dépasse la taille max (~512*4 = 2048 chars)
        String longParagraph = "A".repeat(3000);
        List<TextChunk> chunks = chunkingService.chunk(longParagraph, "big.txt", Map.of());
        assertThat(chunks.size()).isGreaterThan(1);
        // Chaque chunk ne dépasse pas ~2300 chars (taille max + un peu de marge)
        chunks.forEach(c -> assertThat(c.text().length()).isLessThanOrEqualTo(2500));
    }

    @Test
    void chunk_multipleParagraphsMergedUntilMaxSize() {
        // Trois paragraphes courts doivent tenir dans un seul chunk
        String text = "Paragraphe A.\n\nParagraphe B.\n\nParagraphe C.";
        List<TextChunk> chunks = chunkingService.chunk(text, "file.txt", Map.of());
        // Assez petit pour tenir dans un seul chunk
        assertThat(chunks).hasSizeLessThanOrEqualTo(1);
        assertThat(chunks.get(0).text()).contains("Paragraphe A.");
        assertThat(chunks.get(0).text()).contains("Paragraphe B.");
    }

    @Test
    void chunk_propagatesExtraMetadata() {
        Map<String, String> extra = Map.of("author", "Test", "lang", "fr");
        List<TextChunk> chunks = chunkingService.chunk("Texte.", "file.txt", extra);
        assertThat(chunks.get(0).metadata()).containsEntry("author", "Test");
        assertThat(chunks.get(0).metadata()).containsEntry("lang", "fr");
    }

    @Test
    void chunk_noInfiniteLoopOnSingleWordLine() {
        // Régression : splitLargeParagraph ne doit pas boucler si pas d'espace
        String noSpace = "A".repeat(5000);
        List<TextChunk> chunks = chunkingService.chunk(noSpace, "file.txt", Map.of());
        assertThat(chunks).isNotEmpty();
    }
}
