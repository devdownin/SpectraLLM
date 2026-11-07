package fr.spectra.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.dto.QueryResponse;
import fr.spectra.service.ChromaDbClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** A document reference pins a collection, source, revision and optional archive entry. */
final class McpDocumentRead {
    private static final int PAGE_SIZE = 8;
    private static final int MAX_INDEX = Integer.MAX_VALUE - PAGE_SIZE - 1;
    private static final int MAX_REFERENCE_LENGTH = 16_384;
    private static final ObjectMapper JSON = new ObjectMapper();

    record Reference(String collection, String sourceFile, String revision, String zipEntry) {}
    record Cursor(String documentId, int chunkIndex, int characterOffset) {}
    record Chunk(int chunkIndex, int characterOffset, String text, boolean continued) {}
    record Page(String documentId, String sourceFile, String revision, List<Chunk> chunks, String nextCursor) {}

    private McpDocumentRead() {}

    static String reference(String collection, QueryResponse.Source source) {
        var provenance = source.provenance();
        if (provenance == null || !validRevision(provenance.revision())
                || source.sourceFile() == null || source.sourceFile().isBlank()
                || missingArchiveEntry(source.sourceFile(), provenance.zipEntry())) return null;
        return encode(new Reference(collection, source.sourceFile(), provenance.revision(), provenance.zipEntry()));
    }

    static Reference parseReference(String value) {
        Reference ref = decode(value, Reference.class);
        if (ref.collection() == null || ref.collection().isBlank()
                || ref.sourceFile() == null || ref.sourceFile().isBlank() || !validRevision(ref.revision())
                || missingArchiveEntry(ref.sourceFile(), ref.zipEntry())) {
            throw new IllegalArgumentException("Invalid documentId");
        }
        return ref;
    }

    static Page read(ChromaDbClient chroma, String collectionId, Reference ref, String documentId,
                     Integer aroundChunk, String cursorValue, int maxChars) {
        if (aroundChunk != null && cursorValue != null) {
            throw new IllegalArgumentException("Use aroundChunk or cursor, not both");
        }
        Cursor cursor = cursorValue == null
                ? new Cursor(documentId, aroundChunk == null ? 0 : Math.max(0, aroundChunk - 2), 0)
                : decode(cursorValue, Cursor.class);
        if ((aroundChunk != null && (aroundChunk < 0 || aroundChunk > MAX_INDEX))
                || !documentId.equals(cursor.documentId()) || cursor.chunkIndex() < 0
                || cursor.chunkIndex() > MAX_INDEX || cursor.characterOffset() < 0) {
            throw new IllegalArgumentException("Invalid document cursor or chunk index");
        }
        int start = cursor.chunkIndex();
        List<Integer> positions = IntStream.rangeClosed(start, start + PAGE_SIZE).boxed().toList();
        Map<Integer, String> stored = chroma.getChunksByIndex(collectionId, "sha256", ref.revision(),
                positions, ref.zipEntry(), ref.sourceFile());
        if (stored.isEmpty()) {
            throw new IllegalArgumentException("No passages found at this position for the requested document revision");
        }
        var result = new ArrayList<Chunk>();
        int remaining = maxChars;
        String next = null;
        for (int index : positions) {
            String text = stored.get(index);
            if (text == null) continue;
            int offset = index == start ? cursor.characterOffset() : 0;
            if (offset > text.length() || (offset > 0 && offset < text.length()
                    && Character.isLowSurrogate(text.charAt(offset)))) {
                throw new IllegalArgumentException("Invalid character offset");
            }
            if (index == start + PAGE_SIZE || remaining == 0) {
                next = encode(new Cursor(documentId, index, offset));
                break;
            }
            int end = offset + Math.min(remaining, text.length() - offset);
            if (end < text.length() && end > offset && Character.isLowSurrogate(text.charAt(end))) end--;
            boolean continued = end < text.length();
            if (end > offset) result.add(new Chunk(index, offset, text.substring(offset, end), continued));
            remaining -= end - offset;
            if (continued) {
                next = encode(new Cursor(documentId, index, end));
                break;
            }
        }
        return new Page(documentId, ref.sourceFile(), ref.revision(), List.copyOf(result), next);
    }

    private static boolean missingArchiveEntry(String sourceFile, String zipEntry) {
        return sourceFile.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
                && (zipEntry == null || zipEntry.isBlank());
    }

    private static boolean validRevision(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private static String encode(Object value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(value));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not encode document reference", e);
        }
    }

    private static <T> T decode(String value, Class<T> type) {
        if (value != null && value.length() <= MAX_REFERENCE_LENGTH) {
            try {
                T decoded = JSON.readValue(Base64.getUrlDecoder().decode(value), type);
                if (decoded != null) return decoded;
            } catch (java.io.IOException | IllegalArgumentException ignored) {
                // Give the caller a predictable error without including the supplied token.
            }
        }
        throw new IllegalArgumentException("Invalid document reference or cursor");
    }
}
