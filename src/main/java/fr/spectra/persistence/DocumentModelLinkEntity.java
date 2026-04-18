package fr.spectra.persistence;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * R1 — Table de liaison document ↔ modèle.
 * Enregistre quel modèle a été alimenté (entraîné / évalué) par quel document.
 */
@Entity
@Table(name = "document_model_links",
       uniqueConstraints = @UniqueConstraint(columnNames = {"documentSha256", "modelName", "linkType"}))
public class DocumentModelLinkEntity {

    public enum LinkType { TRAINED_ON, EVALUATED_ON }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String documentSha256;

    @Column(nullable = false)
    private String modelName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LinkType linkType;

    private Instant linkedAt;

    protected DocumentModelLinkEntity() {}

    public DocumentModelLinkEntity(String documentSha256, String modelName,
                                   LinkType linkType, Instant linkedAt) {
        this.documentSha256 = documentSha256;
        this.modelName = modelName;
        this.linkType = linkType;
        this.linkedAt = linkedAt;
    }

    public Long getId()               { return id; }
    public String getDocumentSha256() { return documentSha256; }
    public String getModelName()      { return modelName; }
    public LinkType getLinkType()     { return linkType; }
    public Instant getLinkedAt()      { return linkedAt; }
}
