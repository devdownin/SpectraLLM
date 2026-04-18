package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.model.TextChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Découpage sémantique du texte en chunks avec chevauchement.
 * Approximation : 1 token ≈ 4 caractères (pour le français).
 */
@Service
public class ChunkingService {

    private static final int CHARS_PER_TOKEN = 4;

    private final int maxChunkChars;
    private final int overlapChars;

    public ChunkingService(SpectraProperties properties) {
        this.maxChunkChars = properties.pipeline().chunkMaxTokens() * CHARS_PER_TOKEN;
        this.overlapChars = properties.pipeline().chunkOverlapTokens() * CHARS_PER_TOKEN;
    }

    /** Constructor with default chunk/overlap sizes — used in tests. */
    ChunkingService() {
        this.maxChunkChars = 512 * CHARS_PER_TOKEN;
        this.overlapChars = 64 * CHARS_PER_TOKEN;
    }

    public List<TextChunk> chunk(String text, String sourceFile, Map<String, String> extraMetadata) {
        List<TextChunk> chunks = new ArrayList<>();

        if (text == null || text.isBlank()) {
            return chunks;
        }

        // Découpage par paragraphes d'abord
        String[] paragraphs = text.split("\\n\\n+");
        StringBuilder buffer = new StringBuilder();
        int chunkIndex = 0;

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.strip();
            if (trimmed.isEmpty()) continue;

            // Si le paragraphe seul dépasse la taille max, on le découpe
            if (trimmed.length() > maxChunkChars) {
                // Flush le buffer courant
                if (!buffer.isEmpty()) {
                    chunks.add(createChunk(buffer.toString(), chunkIndex++, sourceFile, extraMetadata));
                    buffer.setLength(0);
                }
                // Découpe le grand paragraphe
                chunks.addAll(splitLargeParagraph(trimmed, chunkIndex, sourceFile, extraMetadata));
                chunkIndex = chunks.size();
                continue;
            }

            // Si ajouter ce paragraphe dépasse la taille, on flush
            if (buffer.length() + trimmed.length() + 2 > maxChunkChars) {
                chunks.add(createChunk(buffer.toString(), chunkIndex++, sourceFile, extraMetadata));

                // Chevauchement : on garde la fin du buffer précédent
                String overlap = extractOverlap(buffer.toString());
                buffer.setLength(0);
                buffer.append(overlap);
            }

            if (!buffer.isEmpty()) {
                buffer.append("\n\n");
            }
            buffer.append(trimmed);
        }

        // Dernier chunk
        if (!buffer.isEmpty()) {
            chunks.add(createChunk(buffer.toString(), chunkIndex, sourceFile, extraMetadata));
        }

        return chunks;
    }

    private TextChunk createChunk(String text, int index, String sourceFile, Map<String, String> extraMetadata) {
        Map<String, String> metadata = new HashMap<>(extraMetadata);
        metadata.put("chunkIndex", String.valueOf(index));

        return new TextChunk(
                UUID.randomUUID().toString(),
                text.strip(),
                index,
                sourceFile,
                metadata
        );
    }

    private List<TextChunk> splitLargeParagraph(String text, int startIndex, String sourceFile, Map<String, String> meta) {
        List<TextChunk> chunks = new ArrayList<>();
        int offset = 0;
        int index = startIndex;

        while (offset < text.length()) {
            int end = Math.min(offset + maxChunkChars, text.length());

            // Essayer de couper sur un espace
            if (end < text.length()) {
                int lastSpace = text.lastIndexOf(' ', end);
                if (lastSpace > offset) {
                    end = lastSpace;
                }
            }

            chunks.add(createChunk(text.substring(offset, end), index++, sourceFile, meta));

            if (end >= text.length()) break;

            offset = end - overlapChars;
            if (offset <= 0 || offset >= end) offset = end;
        }

        return chunks;
    }

    private String extractOverlap(String text) {
        if (text.length() <= overlapChars) {
            return text;
        }
        String tail = text.substring(text.length() - overlapChars);
        // Chercher le début du premier mot complet
        int firstSpace = tail.indexOf(' ');
        return firstSpace > 0 ? tail.substring(firstSpace + 1) : tail;
    }
}
