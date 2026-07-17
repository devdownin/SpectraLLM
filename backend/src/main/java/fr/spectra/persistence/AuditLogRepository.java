package fr.spectra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    List<AuditLogEntity> findByDocumentSha256OrderByTimestampDesc(String documentSha256);

    /** Suppression en masse côté SQL (index idx_ged_audit_sha256) — pas de chargement d'entités. */
    @Modifying
    @Query("DELETE FROM AuditLogEntity a WHERE a.documentSha256 = :sha256")
    void deleteByDocumentSha256(String sha256);
}
