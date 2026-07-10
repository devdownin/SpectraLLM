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
 * Découpage du texte en chunks — l'unité de base que le RAG retrouve et cite.
 *
 * <p><b>Pourquoi découper ?</b> On ne peut pas vectoriser un document entier : la fenêtre de
 * contexte du modèle d'embedding est limitée, et un vecteur unique pour 50 pages dilue le sens
 * (tout se ressemble, la recherche perd en précision). On découpe donc en passages courts et
 * cohérents ; chaque chunk reçoit son propre embedding et devient retrouvable indépendamment.</p>
 *
 * <p><b>Pourquoi le chevauchement (overlap) ?</b> Une coupe « sèche » risque de séparer une
 * idée de son contexte (une réponse de sa question, un pronom de son référent). En faisant se
 * recouvrir les chunks de quelques tokens, on garantit qu'une information à cheval sur une
 * frontière reste présente, entière, dans au moins un chunk.</p>
 *
 * <p><b>Comment.</b> La taille est mesurée en <i>tokens</i> (et non en caractères) via une
 * tokenization exacte (jtokkit), pour coller à la vraie limite du modèle. Les coupes sont
 * placées de préférence sur des limites de phrases ({@link java.text.BreakIterator}) afin de
 * ne pas trancher au milieu d'une phrase.</p>
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
        // Garantit que le filtre `where sourceFile == X` de ChromaDbClient.deleteBySource
        // fonctionne quel que soit le format : seul TxtExtractor peuplait cette métadonnée,
        // ce qui cassait silencieusement la suppression/upsert par source pour PDF, DOCX,
        // JSON, Avro, XML… (le champ TextChunk.sourceFile suffit côté BM25 mais pas côté vecteur).
        if (sourceFile != null) {
            metadata.put("sourceFile", sourceFile);
        }

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
