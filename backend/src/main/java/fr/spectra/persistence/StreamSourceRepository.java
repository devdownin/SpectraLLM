package fr.spectra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface StreamSourceRepository extends JpaRepository<StreamSourceEntity, String> {

    /** Sources non mises à jour depuis {@code cutoff} — politique de rétention du flux. */
    List<StreamSourceEntity> findByLastUpdatedAtBefore(Instant cutoff);
}
