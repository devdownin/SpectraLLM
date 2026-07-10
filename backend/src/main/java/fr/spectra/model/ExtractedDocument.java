package fr.spectra.model;

import java.time.Instant;
import java.util.Map;

/**
 * Résultat de l'extraction brute d'un document.
 */
public record ExtractedDocument(
        String sourceFile,
        String contentType,
        String text,
        int pageCount,
        Map<String, String> metadata,
        Instant extractedAt
) {
    public ExtractedDocument(String sourceFile, String contentType, String text, int pageCount, Map<String, String> metadata) {
        this(sourceFile, contentType, text, pageCount, metadata, Instant.now());
    }
}
