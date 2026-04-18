package fr.spectra.model;

import java.util.Map;

/**
 * Segment de texte découpé avec ses métadonnées.
 */
public record TextChunk(
        String id,
        String text,
        int index,
        String sourceFile,
        Map<String, String> metadata
) {
}
