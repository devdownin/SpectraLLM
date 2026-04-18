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

    List<IngestedFileEntity> findByCollectionNameOrderByIngestedAtDesc(String collectionName);

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
}
