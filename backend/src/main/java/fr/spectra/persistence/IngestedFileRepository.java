package fr.spectra.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface IngestedFileRepository
        extends JpaRepository<IngestedFileEntity, String>,
                JpaSpecificationExecutor<IngestedFileEntity> {

    List<IngestedFileEntity> findAllByOrderByIngestedAtDesc();

    List<IngestedFileEntity> findByLifecycleOrderByIngestedAtDesc(IngestedFileEntity.Lifecycle lifecycle);

    /** Filtre lifecycle + date côté SQL (politique de rétention) — évite de tout charger en mémoire. */
    List<IngestedFileEntity> findByLifecycleAndIngestedAtBefore(
            IngestedFileEntity.Lifecycle lifecycle, java.time.Instant cutoff);

    List<IngestedFileEntity> findByCollectionNameOrderByIngestedAtDesc(String collectionName);

    Page<IngestedFileEntity> findByFileNameContainingIgnoreCase(String fileName, Pageable pageable);

    /** Documents portant exactement ce nom de fichier (suppression par nom de source). */
    List<IngestedFileEntity> findByFileName(String fileName);

    Page<IngestedFileEntity> findAll(
            org.springframework.data.jpa.domain.Specification<IngestedFileEntity> spec,
            Pageable pageable);

    // Stats : nombre de documents par lifecycle
    @Query("SELECT f.lifecycle, COUNT(f) FROM IngestedFileEntity f GROUP BY f.lifecycle")
    List<Object[]> countByLifecycle();

    // Stats : score qualité moyen
    @Query("SELECT AVG(f.qualityScore) FROM IngestedFileEntity f WHERE f.qualityScore IS NOT NULL")
    Double avgQualityScore();

    // Stats : total chunks
    @Query("SELECT SUM(f.chunksCreated) FROM IngestedFileEntity f")
    Long sumChunks();

    // Stats : distribution qualité — comptages par tranche, sans chargement en mémoire
    @Query("SELECT COUNT(f) FROM IngestedFileEntity f WHERE f.qualityScore IS NOT NULL AND f.qualityScore < 0.25")
    Long countQualityQ0();

    @Query("SELECT COUNT(f) FROM IngestedFileEntity f WHERE f.qualityScore IS NOT NULL AND f.qualityScore >= 0.25 AND f.qualityScore < 0.5")
    Long countQualityQ1();

    @Query("SELECT COUNT(f) FROM IngestedFileEntity f WHERE f.qualityScore IS NOT NULL AND f.qualityScore >= 0.5 AND f.qualityScore < 0.75")
    Long countQualityQ2();

    @Query("SELECT COUNT(f) FROM IngestedFileEntity f WHERE f.qualityScore IS NOT NULL AND f.qualityScore >= 0.75")
    Long countQualityQ3();

    // Stats : uniquement la colonne tags — évite de charger les entités complètes
    @Query("SELECT f.tags FROM IngestedFileEntity f WHERE f.tags IS NOT NULL AND f.tags <> '[]'")
    List<String> findAllTagsJson();
}
