package fr.spectra.sources;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface SourceDocumentRepository extends JpaRepository<SourceDocument, String> {
    long countBySourceIdAndIndexedAtIsNotNull(String sourceId);
    Page<SourceDocument> findBySourceIdOrderByTitleAsc(String sourceId, Pageable page);
    Page<SourceDocument> findBySourceIdAndSeenRunNot(String sourceId, String seenRun, Pageable page);
    List<SourceDocument> findBySourceId(String sourceId);
}
