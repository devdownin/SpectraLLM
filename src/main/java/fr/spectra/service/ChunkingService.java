package fr.spectra.service;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import fr.spectra.config.SpectraProperties;
import fr.spectra.model.TextChunk;
import org.springframework.stereotype.Service;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Découpage sémantique du texte en chunks avec chevauchement.
 * Utilise la tokenization exacte via la bibliothèque jtokkit.
 * Découpe préférentiellement sur les limites de phrases via BreakIterator.
 */
@Service
public class ChunkingService {

    private final Encoding encoding;
    private final int maxChunkTokens;
    private final int overlapTokens;

    public ChunkingService(SpectraProperties properties) {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
        this.maxChunkTokens = properties.pipeline().chunkMaxTokens();
        this.overlapTokens = properties.pipeline().chunkOverlapTokens();
    }

    /** Constructor with default chunk/overlap sizes — used in tests. */
    ChunkingService() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
        this.maxChunkTokens = 512;
        this.overlapTokens = 64;
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

            IntArrayList paraTokens = encoding.encode(trimmed);

            // Si le paragraphe seul dépasse la taille max, on le découpe
            if (paraTokens.size() > maxChunkTokens) {
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
            IntArrayList bufferTokens = encoding.encode(buffer.toString());
            int sepTokens = buffer.isEmpty() ? 0 : encoding.encode("\n\n").size();
            
            if (bufferTokens.size() + sepTokens + paraTokens.size() > maxChunkTokens) {
                chunks.add(createChunk(buffer.toString(), chunkIndex++, sourceFile, extraMetadata));

                // Chevauchement : on garde la fin du buffer précédent
                IntArrayList currentTokens = encoding.encode(buffer.toString());
                int startIdx = Math.max(0, currentTokens.size() - overlapTokens);
                String overlap = encoding.decode(subList(currentTokens, startIdx, currentTokens.size()));
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
        List<String> sentences = new ArrayList<>();
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.FRENCH);
        iterator.setText(text);
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = text.substring(start, end).strip();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }

        List<TextChunk> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        int index = startIndex;

        for (String sentence : sentences) {
            IntArrayList sentenceTokens = encoding.encode(sentence);

            // Si une phrase isolée dépasse la taille maximale, on la découpe par tokens
            if (sentenceTokens.size() > maxChunkTokens) {
                // Flush du chunk courant s'il n'est pas vide
                if (!currentChunk.isEmpty()) {
                    chunks.add(createChunk(currentChunk.toString(), index++, sourceFile, meta));
                    currentChunk.setLength(0);
                }
                // Découpage par tokens
                List<TextChunk> subChunks = splitLargeTextByTokens(sentence, index, sourceFile, meta);
                chunks.addAll(subChunks);
                index = startIndex + chunks.size();
                continue;
            }

            IntArrayList currentChunkTokens = encoding.encode(currentChunk.toString());
            int spaceTokens = currentChunk.isEmpty() ? 0 : encoding.encode(" ").size();

            // Si l'ajout de cette phrase dépasse la limite du chunk
            if (currentChunkTokens.size() + spaceTokens + sentenceTokens.size() > maxChunkTokens) {
                chunks.add(createChunk(currentChunk.toString(), index++, sourceFile, meta));

                // Application du chevauchement (overlap)
                IntArrayList currentTokens = encoding.encode(currentChunk.toString());
                int startIdx = Math.max(0, currentTokens.size() - overlapTokens);
                String overlap = encoding.decode(subList(currentTokens, startIdx, currentTokens.size()));
                currentChunk.setLength(0);
                currentChunk.append(overlap);
            }

            if (!currentChunk.isEmpty() && !currentChunk.toString().endsWith(" ") && !sentence.startsWith(" ")) {
                currentChunk.append(" ");
            }
            currentChunk.append(sentence);
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(createChunk(currentChunk.toString(), index, sourceFile, meta));
        }

        return chunks;
    }

    private List<TextChunk> splitLargeTextByTokens(String text, int startIndex, String sourceFile, Map<String, String> meta) {
        List<TextChunk> chunks = new ArrayList<>();
        IntArrayList tokens = encoding.encode(text);
        int offset = 0;
        int index = startIndex;

        while (offset < tokens.size()) {
            int end = Math.min(offset + maxChunkTokens, tokens.size());
            IntArrayList subTokens = subList(tokens, offset, end);
            String chunkText = encoding.decode(subTokens);

            chunks.add(createChunk(chunkText, index++, sourceFile, meta));

            if (end >= tokens.size()) break;

            // Avance avec chevauchement, garantissant une progression STRICTE.
            int nextOffset = end - overlapTokens;
            if (nextOffset <= offset) {
                nextOffset = end;
            }
            offset = nextOffset;
        }

        return chunks;
    }

    private IntArrayList subList(IntArrayList list, int from, int to) {
        IntArrayList sub = new IntArrayList();
        for (int i = from; i < to; i++) {
            sub.add(list.get(i));
        }
        return sub;
    }
}
