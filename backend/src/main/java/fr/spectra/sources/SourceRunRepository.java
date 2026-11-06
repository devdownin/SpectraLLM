package fr.spectra.sources;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface SourceRunRepository extends JpaRepository<SourceRun, String> {
    List<SourceRun> findTop20BySourceIdOrderByStartedAtDesc(String sourceId);
    Optional<SourceRun> findFirstBySourceIdOrderByStartedAtDesc(String sourceId);
    List<SourceRun> findByStatus(SourceModels.Status status);
}
