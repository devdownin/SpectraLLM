package fr.spectra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionTaskRepository extends JpaRepository<IngestionTaskEntity, String> {}
