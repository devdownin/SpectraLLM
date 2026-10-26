package fr.spectra.service;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Identity of a contiguous document, narrower than the archive identity used for deletion. */
record ChunkNeighborScope(String identityField, String identityValue, String zipEntry) {
    static Optional<ChunkNeighborScope> from(Map<String, String> metadata, boolean requireDocumentIdentity) {
        String sha = nonBlank(metadata.get("sha256"));
        String source = nonBlank(metadata.get("sourceFile"));
        String entry = nonBlank(metadata.get("zipEntry"));
        if (requireDocumentIdentity && sha == null) return Optional.empty();
        // Old archive chunks have no reliable sub-document boundary. Do not guess their neighbors.
        if (entry == null && (metadata.containsKey("zipEntry")
                || source != null && source.toLowerCase(Locale.ROOT).endsWith(".zip"))) {
            return Optional.empty();
        }
        if (sha != null) return Optional.of(new ChunkNeighborScope("sha256", sha, entry));
        return source != null ? Optional.of(new ChunkNeighborScope("sourceFile", source, entry)) : Optional.empty();
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
