package fr.spectra.dto;

import java.time.Instant;
import java.util.List;

/**
 * Filtre de recherche appliqué <b>avant</b> le classement : seuls les chunks des documents retenus
 * sont candidats, côté vecteur comme côté BM25.
 *
 * <p>Les critères se combinent en ET ; à l'intérieur d'une liste, en OU. {@code categories} et les
 * bornes de date portent sur la fiche GED du document (classification R8, date d'ingestion) ;
 * {@code sources} sur le nom de fichier source, ce qui couvre aussi les sources streaming.</p>
 *
 * @param categories     catégories GED acceptées (au moins une)
 * @param sources        fichiers sources acceptés (nom exact, tel que listé par {@code /api/documents})
 * @param ingestedAfter  ingestion au plus tôt à cet instant (inclus)
 * @param ingestedBefore ingestion au plus tard à cet instant (exclu)
 */
public record RetrievalFilter(List<String> categories, List<String> sources,
                              Instant ingestedAfter, Instant ingestedBefore) {

    public static final RetrievalFilter NONE = new RetrievalFilter(null, null, null, null);

    public RetrievalFilter {
        categories = categories == null ? List.of() : List.copyOf(categories);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public boolean isEmpty() {
        return categories.isEmpty() && sources.isEmpty() && ingestedAfter == null && ingestedBefore == null;
    }

    /** Critères qui exigent la fiche GED du document (le nom de source seul ne suffit pas). */
    public boolean needsDocumentRecords() {
        return !categories.isEmpty() || ingestedAfter != null || ingestedBefore != null;
    }
}
