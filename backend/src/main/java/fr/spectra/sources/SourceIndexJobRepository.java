package fr.spectra.sources;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface SourceIndexJobRepository extends JpaRepository<SourceIndexJob, String> {
    Optional<SourceIndexJob> findFirstBySourceIdAndRunIdOrderById(String sourceId, String runId);

    @Transactional
    @Modifying
    @Query("delete from SourceIndexJob job where job.sourceId = :sourceId")
    void clearSource(String sourceId);
}
