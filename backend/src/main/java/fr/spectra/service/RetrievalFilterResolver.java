package fr.spectra.service;

import fr.spectra.dto.RetrievalFilter;
import fr.spectra.persistence.IngestedFileEntity;
import fr.spectra.persistence.IngestedFileRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Traduit un {@link RetrievalFilter} en identités de documents, applicables tels quels au filtre
 * {@code where} de ChromaDB et à l'index BM25.
 *
 * <p>Les catégories GED et la date d'ingestion ne vivent que sur la fiche du document
 * ({@link IngestedFileEntity}), pas dans les métadonnées des chunks : les recopier dans chaque
 * chunk obligerait à réécrire l'index à chaque reclassification. On résout donc la fiche en
 * {@code sha256} — l'identité que portent les chunks — et en nom de source pour les chunks
 * historiques qui n'ont pas d'empreinte.</p>
 */
@Service
public class RetrievalFilterResolver {

    private final IngestedFileRepository repository;

    public RetrievalFilterResolver(IngestedFileRepository repository) {
        this.repository = repository;
    }

    /**
     * @param defaultCollection {@code true} si la collection interrogée est la collection par
     *                          défaut : les fiches historiques sans collection lui appartiennent
     */
    public ResolvedFilter resolve(RetrievalFilter filter, String collectionName, boolean defaultCollection) {
        if (filter == null || filter.isEmpty()) return ResolvedFilter.NONE;
        if (!filter.needsDocumentRecords()) {
            return new ResolvedFilter(null, new LinkedHashSet<>(filter.sources()));
        }
        Set<String> categories = filter.categories().stream()
                .map(c -> c.strip().toLowerCase(java.util.Locale.ROOT)).collect(Collectors.toSet());
        List<IngestedFileEntity> documents = repository.findAllByOrderByIngestedAtDesc().stream()
                .filter(d -> Objects.equals(d.getCollectionName(), collectionName)
                        || (d.getCollectionName() == null && defaultCollection))
                .filter(d -> categories.isEmpty() || d.getCategories().stream()
                        .anyMatch(c -> categories.contains(c.toLowerCase(java.util.Locale.ROOT))))
                .filter(d -> filter.sources().isEmpty() || filter.sources().contains(d.getFileName()))
                .filter(d -> filter.ingestedAfter() == null
                        || (d.getIngestedAt() != null && !d.getIngestedAt().isBefore(filter.ingestedAfter())))
                .filter(d -> filter.ingestedBefore() == null
                        || (d.getIngestedAt() != null && d.getIngestedAt().isBefore(filter.ingestedBefore())))
                .toList();
        return new ResolvedFilter(
                documents.stream().map(IngestedFileEntity::getSha256).filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                documents.stream().map(IngestedFileEntity::getFileName).filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    /**
     * Filtre résolu. {@code sha256s} {@code null} = pas de contrainte d'identité ; {@code sourceFiles}
     * seul = filtre par nom de source. Avec les deux, un chunk est retenu par son empreinte, ou par
     * sa source s'il n'en porte pas (chunk historique).
     */
    public record ResolvedFilter(Set<String> sha256s, Set<String> sourceFiles) {

        public static final ResolvedFilter NONE = new ResolvedFilter(null, null);

        public boolean active() {
            return sha256s != null || sourceFiles != null;
        }

        /** Aucun document ne satisfait le filtre : inutile d'interroger les index. */
        public boolean matchesNothing() {
            return active() && (sha256s == null || sha256s.isEmpty()) && (sourceFiles == null || sourceFiles.isEmpty());
        }

        /**
         * Prefilter ChromaDB (superset for legacy chunks without an identity).
         * Always use {@link FilteredVectorSearch} to enforce {@link #accepts} before ranking.
         */
        public Map<String, Object> chromaWhere() {
            if (!active()) return null;
            Map<String, Object> bySha = sha256s != null && !sha256s.isEmpty()
                    ? Map.of("sha256", Map.of("$in", List.copyOf(sha256s))) : null;
            Map<String, Object> bySource = sourceFiles != null && !sourceFiles.isEmpty()
                    ? Map.of("sourceFile", Map.of("$in", List.copyOf(sourceFiles))) : null;
            if (bySha != null && bySource != null) return Map.of("$or", List.of(bySha, bySource));
            return bySha != null ? bySha : bySource;
        }

        /** Un chunk ({@code sha256} nullable, source) est-il retenu ? Même règle que {@link #chromaWhere()}. */
        public boolean accepts(String sha256, String sourceFile) {
            if (!active()) return true;
            if (sha256s != null && sha256 != null && sha256s.contains(sha256)) return true;
            if (sha256s != null && sha256 != null) return false;
            return sourceFiles != null && sourceFile != null && sourceFiles.contains(sourceFile);
        }
    }
}
