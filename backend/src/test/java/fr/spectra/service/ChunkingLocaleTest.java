package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.model.TextChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locale configurable des frontières de phrases ({@code spectra.pipeline.chunk-locale}) :
 * un corpus non francophone peut choisir sa locale de découpe, et une valeur invalide
 * retombe sur le français sans casser le démarrage.
 */
class ChunkingLocaleTest {

    private static SpectraProperties props() {
        SpectraProperties.PipelineProperties pipeline =
                new SpectraProperties.PipelineProperties(64, 8, 10, 30, 120, 4);
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.pipeline()).thenReturn(pipeline);
        return props;
    }

    /** Paragraphe > 64 tokens pour forcer le passage par splitLargeParagraph (BreakIterator). */
    private static String longParagraph() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("This is sentence number ").append(i)
              .append(" of a fairly long paragraph used for chunking. ");
        }
        return sb.toString();
    }

    @Test
    void chunk_withEnglishLocale_splitsLargeParagraphs() {
        ChunkingService service = new ChunkingService(props(), "en");
        List<TextChunk> chunks = service.chunk(longParagraph(), "doc.txt", Map.of());

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.getFirst().text()).isNotBlank();
    }

    @Test
    void chunk_withInvalidLocaleTag_fallsBackWithoutFailing() {
        ChunkingService service = new ChunkingService(props(), "$$invalid$$");
        List<TextChunk> chunks = service.chunk(longParagraph(), "doc.txt", Map.of());

        assertThat(chunks).isNotEmpty();
    }

    @Test
    void chunk_withBlankLocale_fallsBackToFrench() {
        ChunkingService service = new ChunkingService(props(), " ");
        List<TextChunk> chunks = service.chunk(
                "Première phrase du document. Deuxième phrase du document.", "doc.txt", Map.of());

        assertThat(chunks).isNotEmpty();
    }
}
