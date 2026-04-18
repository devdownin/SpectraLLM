package fr.spectra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationTaskRepository extends JpaRepository<GenerationTaskEntity, String> {}
