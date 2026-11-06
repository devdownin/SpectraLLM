package fr.spectra.sources;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="source_documents", indexes=@Index(name="idx_source_docs_instance", columnList="source_id"))
public class SourceDocument {
    @Id @Column(length=64) public String id;
    @Column(name="source_id", nullable=false) public String sourceId;
    @Column(length=2048) public String externalId;
    @Column(length=512) public String title;
    @Column(length=2048) public String sourceUrl;
    @Column(length=128) public String revision;
    @Column(length=64) public String fingerprint;
    public Instant indexedAt;
    public Instant lastSeenAt;
    public String seenRun;
    public String status;
    @Column(length=1000) public String error;
    public SourceDocument() {}
    public SourceModels.Document toDto() {
        return new SourceModels.Document(id, externalId, title, sourceUrl, revision, indexedAt, lastSeenAt, status, error);
    }
    public String sourceKey() { return "source://" + sourceId + "/" + id; }
}
