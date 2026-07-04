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
        /** Recherche plein-texte sur le nom de fichier (insensible à la casse), côté serveur —
         *  pour retrouver un document au-delà de la page courante. */
        String q,
        int page,
        int size
) {
    public GedDocumentFilter {
        if (page < 0) page = 0;
        // Plafond relevé à 1000 : l'UI peut charger un plus grand lot pour son filtrage client
        // (format/qualité/tri/groupement), la recherche par nom étant déportée côté serveur.
        if (size <= 0 || size > 1000) size = 20;
    }
}
