package fr.spectra.persistence;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * R6 — Audit trail complet de toutes les opérations GED.
 */
@Entity
@Table(name = "ged_audit_log")
public class AuditLogEntity {

    public enum Action {
        INGESTED,
        RE_INGESTED,
        LIFECYCLE_CHANGED,
        TAGGED,
        UNTAGGED,
        LINKED_TO_MODEL,
        ARCHIVED,
        DELETED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String documentSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Action action;

    /** "system" ou identifiant utilisateur. */
    private String actor;

    @Column(nullable = false)
    private Instant timestamp;

    /** Détails contextuels (JSON ou texte libre). */
    @Column(columnDefinition = "TEXT")
    private String details;

    protected AuditLogEntity() {}

    public AuditLogEntity(String documentSha256, Action action,
                          String actor, Instant timestamp, String details) {
        this.documentSha256 = documentSha256;
        this.action = action;
        this.actor = actor;
        this.timestamp = timestamp;
        this.details = details;
    }

    public Long getId()               { return id; }
    public String getDocumentSha256() { return documentSha256; }
    public Action getAction()         { return action; }
    public String getActor()          { return actor; }
    public Instant getTimestamp()     { return timestamp; }
    public String getDetails()        { return details; }
}
