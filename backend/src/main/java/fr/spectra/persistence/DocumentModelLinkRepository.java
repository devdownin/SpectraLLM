package fr.spectra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DocumentModelLinkRepository extends JpaRepository<DocumentModelLinkEntity, Long> {

    List<DocumentModelLinkEntity> findByDocumentSha256(String documentSha256);

    /** Suppression en masse côté SQL (index idx_doc_model_sha256) — pas de chargement d'entités. */
    @Modifying
    @Query("DELETE FROM DocumentModelLinkEntity l WHERE l.documentSha256 = :sha256")
    void deleteByDocumentSha256(String sha256);

    List<DocumentModelLinkEntity> findByModelName(String modelName);

    boolean existsByDocumentSha256AndModelNameAndLinkType(
            String documentSha256, String modelName, DocumentModelLinkEntity.LinkType linkType);
}
