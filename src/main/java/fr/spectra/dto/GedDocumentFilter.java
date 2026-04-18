package fr.spectra.dto;

import fr.spectra.persistence.IngestedFileEntity;

import java.time.Instant;

/**
 * Critères de filtrage combiné pour la liste GED avec pagination.
 */
public record GedDocumentFilter(
        IngestedFileEntity.Lifecycle lifecycle,
        String tag,
        String collection,
        Double minQuality,
        Instant from,
        Instant to,
        int page,
        int size
) {
    public GedDocumentFilter {
        if (page < 0) page = 0;
        if (size <= 0 || size > 200) size = 20;
    }
}
