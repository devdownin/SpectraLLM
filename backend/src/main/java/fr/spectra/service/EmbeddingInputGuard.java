package fr.spectra.service;

import fr.spectra.model.TextChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Garantit que chaque texte embeddé tient dans la fenêtre du modèle, mesurée avec <b>son</b>
 * tokenizer.
 *
 * <p><b>Pourquoi.</b> Le chunking compte en cl100k (rapide, local), mais le modèle d'embedding a
 * son propre vocabulaire : WordPiece pour nomic, qui découpe le français bien plus finement. S'y
 * ajoutent le préfixe de tâche et l'en-tête contextuel. Un chunk « de 512 tokens » peut donc en
 * faire beaucoup plus pour le modèle ; au-delà de la fenêtre, llama-server rejette le lot, ou
 * tronque silencieusement selon sa configuration — dans les deux cas, du texte cité n'est pas
 * représenté dans son vecteur.</p>
 *
 * <p><b>Comment.</b> Une borne sûre évite tout appel dans le cas courant (un token couvre au moins
 * un octet UTF-8). Au-delà, les textes sont comptés par {@code /tokenize} ; un chunk qui dépasse
 * est redécoupé par phrases jusqu'à tenir, puis les {@code chunkIndex} du document sont
 * renumérotés. En ré-indexation, où l'identité des chunks est figée, le texte embeddé est tronqué
 * au lieu d'être redécoupé — le texte stocké et cité reste intact.</p>
 *
 * <p>Tokenizer indisponible : rien n'est vérifié (avertissement unique), comportement inchangé.</p>
 */
public final class EmbeddingInputGuard {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingInputGuard.class);

    /** Tokens spéciaux ([CLS]/[SEP], BOS/EOS, ▁ initial) qui ne couvrent aucun octet du texte. */
    private static final int SPECIAL_TOKENS_MARGIN = 4;
    /** Marge sur l'estimation de découpe ; le tokenizer tranche ensuite. */
    private static final double SAFETY = 0.9;
    private static final int MAX_SPLIT_DEPTH = 4;
    private static final int MIN_PIECE_CHARS = 50;

    private final EmbeddingService embeddingService;
    private final boolean contextualHeaders;
    private final AtomicBoolean warnedNoTokenizer = new AtomicBoolean();

    public EmbeddingInputGuard(EmbeddingService embeddingService, boolean contextualHeaders) {
        this.embeddingService = embeddingService;
        this.contextualHeaders = contextualHeaders;
    }

    /** Question préfixée selon le contrat, tronquée si elle dépasse la fenêtre du modèle. */
    public String queryText(String question, EmbeddingContract contract) {
        return truncateToFit(List.of(contract.prompts().query(question)), contract).getFirst();
    }

    /** Texte réellement envoyé au modèle pour un chunk. */
    public String documentText(String text, Map<String, ?> metadata, EmbeddingContract contract) {
        return contract.documentText(text, metadata, contextualHeaders);
    }

    /** Textes réellement envoyés au modèle pour ces chunks. */
    public List<String> documentTexts(List<TextChunk> chunks, EmbeddingContract contract) {
        return chunks.stream()
                .map(c -> contract.documentText(c.text(), c.metadata(), contextualHeaders))
                .toList();
    }

    /**
     * Chunks d'un même document, chacun garanti dans la fenêtre du contrat. Retourne la liste
     * d'origine si rien ne dépasse (ou si le tokenizer ne répond pas).
     */
    public List<TextChunk> fit(List<TextChunk> chunks, EmbeddingContract contract) {
        if (chunks == null || chunks.isEmpty()) return chunks;
        int max = contract.maxInputTokens();
        List<String> texts = documentTexts(chunks, contract);
        if (texts.stream().allMatch(t -> surelyFits(t, max))) return chunks;

        Optional<List<Integer>> counts = count(texts);
        if (counts.isEmpty()) return chunks;
        List<Integer> tokens = counts.get();
        if (tokens.stream().allMatch(n -> n <= max)) return chunks;

        List<TextChunk> fitted = new ArrayList<>(chunks.size());
        int split = 0;
        for (int i = 0; i < chunks.size(); i++) {
            if (tokens.get(i) <= max) {
                fitted.add(chunks.get(i));
            } else {
                fitted.addAll(split(chunks.get(i), texts.get(i), tokens.get(i), contract, 0));
                split++;
            }
        }
        log.info("Fenêtre d'embedding ({} tokens du modèle) : {} chunk(s) redécoupé(s) en {} morceaux",
                max, split, fitted.size() - chunks.size() + split);
        return renumber(fitted, chunks.getFirst().index());
    }

    /**
     * Textes embeddés tronqués à la fenêtre du contrat, pour la ré-indexation : l'identité des
     * chunks y est figée, on ne peut pas les redécouper.
     */
    public List<String> truncateToFit(List<String> embeddedTexts, EmbeddingContract contract) {
        int max = contract.maxInputTokens();
        if (embeddedTexts.stream().allMatch(t -> surelyFits(t, max))) return embeddedTexts;
        Optional<List<Integer>> counts = count(embeddedTexts);
        if (counts.isEmpty()) return embeddedTexts;

        List<String> out = new ArrayList<>(embeddedTexts);
        for (int i = 0; i < out.size(); i++) {
            int tokens = counts.get().get(i);
            String text = out.get(i);
            for (int attempt = 0; tokens > max && attempt < MAX_SPLIT_DEPTH; attempt++) {
                int keep = Math.max(MIN_PIECE_CHARS, (int) (text.length() * (double) max / tokens * SAFETY));
                text = text.substring(0, Math.min(keep, text.length()));
                tokens = count(List.of(text)).map(List::getFirst).orElse(0);
            }
            if (!text.equals(out.get(i))) {
                log.warn("Ré-indexation : texte embeddé tronqué à la fenêtre du modèle ({} tokens)", max);
                out.set(i, text);
            }
        }
        return out;
    }

    private List<TextChunk> split(TextChunk chunk, String embedded, int tokens, EmbeddingContract contract, int depth) {
        double tokensPerChar = (double) tokens / Math.max(1, embedded.length());
        int overheadChars = embedded.length() - chunk.text().length();
        int target = Math.max(MIN_PIECE_CHARS,
                (int) (contract.maxInputTokens() / tokensPerChar * SAFETY) - overheadChars);

        List<String> pieces = pack(chunk.text(), target);
        List<TextChunk> candidates = pieces.stream()
                .map(p -> new TextChunk(UUID.randomUUID().toString(), p, chunk.index(), chunk.sourceFile(),
                        chunk.metadata()))
                .toList();
        List<String> texts = documentTexts(candidates, contract);
        List<Integer> counts = count(texts).orElse(null);
        if (counts == null) return candidates;

        List<TextChunk> result = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (counts.get(i) <= contract.maxInputTokens() || depth + 1 >= MAX_SPLIT_DEPTH) {
                result.add(candidates.get(i));
            } else {
                result.addAll(split(candidates.get(i), texts.get(i), counts.get(i), contract, depth + 1));
            }
        }
        return result;
    }

    /** Regroupe les phrases en morceaux d'au plus {@code target} caractères ; coupe aux blancs une phrase trop longue. */
    static List<String> pack(String text, int target) {
        List<String> sentences = new ArrayList<>();
        BreakIterator it = BreakIterator.getSentenceInstance(Locale.FRENCH);
        it.setText(text);
        for (int start = it.first(), end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            String sentence = text.substring(start, end).strip();
            if (sentence.isEmpty()) continue;
            if (sentence.length() <= target) {
                sentences.add(sentence);
            } else {
                sentences.addAll(cutAtWhitespace(sentence, target));
            }
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (!current.isEmpty() && current.length() + 1 + sentence.length() > target) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) current.append(' ');
            current.append(sentence);
        }
        if (!current.isEmpty()) pieces.add(current.toString());
        return pieces;
    }

    private static List<String> cutAtWhitespace(String text, int target) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + target);
            if (end < text.length()) {
                int space = text.lastIndexOf(' ', end);
                if (space > start) end = space;
            }
            String part = text.substring(start, end).strip();
            if (!part.isEmpty()) parts.add(part);
            start = end;
        }
        return parts;
    }

    private static List<TextChunk> renumber(List<TextChunk> chunks, int firstIndex) {
        List<TextChunk> renumbered = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk c = chunks.get(i);
            int index = firstIndex + i;
            Map<String, String> metadata = new HashMap<>(c.metadata());
            metadata.put("chunkIndex", String.valueOf(index));
            renumbered.add(new TextChunk(c.id(), c.text(), index, c.sourceFile(), metadata));
        }
        return renumbered;
    }

    private static boolean surelyFits(String text, int maxTokens) {
        return text.getBytes(StandardCharsets.UTF_8).length + SPECIAL_TOKENS_MARGIN <= maxTokens;
    }

    private Optional<List<Integer>> count(List<String> texts) {
        Optional<List<Integer>> counts = embeddingService.countTokens(texts);
        if (counts == null || counts.isEmpty() || counts.get().size() != texts.size()) {
            if (warnedNoTokenizer.compareAndSet(false, true)) {
                log.warn("Tokenizer du modèle d'embedding indisponible : la taille des textes embeddés "
                        + "n'est pas vérifiée (un chunk trop long sera rejeté ou tronqué par llm-embed).");
            }
            return Optional.empty();
        }
        return counts;
    }
}
