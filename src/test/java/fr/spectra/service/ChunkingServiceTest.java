package fr.spectra.service;

import fr.spectra.model.TextChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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
    void chunk_propagatesSourceFileIntoMetadataForVectorDelete() {
        // Régression : ChromaDbClient.deleteBySource filtre sur la métadonnée `sourceFile`.
        // Si elle est absente (cas de tous les extracteurs sauf TXT), la suppression/upsert
        // par source ne matche rien côté vecteur. La métadonnée doit donc toujours la porter.
        List<TextChunk> chunks = chunkingService.chunk("Du texte.", "kafka://commandes/dossier-4271", Map.of());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).metadata())
                .containsEntry("sourceFile", "kafka://commandes/dossier-4271");
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
        // Créer un texte qui dépasse la taille max (512 tokens)
        String longParagraph = " hello".repeat(600);
        List<TextChunk> chunks = chunkingService.chunk(longParagraph, "big.txt", Map.of());
        assertThat(chunks.size()).isGreaterThan(1);
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

    @Test
    void chunk_noInfiniteLoopWhenSpaceFollowedByLongRun() {
        // Régression OOM : un espace en début de paragraphe suivi d'une longue
        // séquence sans espace (URL, base64, JSON minifié, table…) figeait
        // `end` sur le même espace → boucle infinie → liste de chunks illimitée → OOM,
        // même sur un document de quelques Ko.
        String pathological = "A".repeat(1500) + " " + "B".repeat(2000);
        List<TextChunk> chunks = assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(5),
                () -> chunkingService.chunk(pathological, "file.txt", Map.of()));
        assertThat(chunks).isNotEmpty();
        // Le texte complet doit être couvert (progression stricte, pas de chunk vide en boucle).
        assertThat(chunks.stream().anyMatch(c -> c.text().contains("B"))).isTrue();
    }

    @Test
    void chunk_splitsBySentenceBoundaries() {
        // Sentence 1 of 400 tokens + Sentence 2 of 200 tokens (Total 600 tokens, exceeds 512 limit)
        String s1 = " hello".repeat(400) + ".";
        String s2 = " world".repeat(200) + ".";
        String text = s1 + " " + s2;
        
        List<TextChunk> chunks = chunkingService.chunk(text, "file.txt", Map.of());
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).text()).isEqualTo(s1.strip());
        assertThat(chunks.get(1).text()).contains("world");
    }
}
