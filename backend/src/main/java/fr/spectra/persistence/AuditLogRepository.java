package fr.spectra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    List<AuditLogEntity> findByDocumentSha256OrderByTimestampDesc(String documentSha256);
}
