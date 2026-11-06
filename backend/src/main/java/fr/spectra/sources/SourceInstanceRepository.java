package fr.spectra.sources;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface SourceInstanceRepository extends JpaRepository<SourceInstance, String> {
    List<SourceInstance> findAllByOrderByNameAsc();
}
