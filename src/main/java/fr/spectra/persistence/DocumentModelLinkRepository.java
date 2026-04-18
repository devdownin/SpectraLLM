package fr.spectra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentModelLinkRepository extends JpaRepository<DocumentModelLinkEntity, Long> {

    List<DocumentModelLinkEntity> findByDocumentSha256(String documentSha256);

    List<DocumentModelLinkEntity> findByModelName(String modelName);

    boolean existsByDocumentSha256AndModelNameAndLinkType(
            String documentSha256, String modelName, DocumentModelLinkEntity.LinkType linkType);
}
