package fr.spectra.sources;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Durable hand-off between source discovery and document publication. */
@Entity
@Table(name = "source_index_jobs")
public class SourceIndexJob {
    @Id @Column(length = 64) String id;
    @Column(nullable = false) String sourceId;
    @Column(nullable = false) String runId;
    @Column(columnDefinition = "TEXT") String filename;
    @Column(length = 128) String revision;
    @Column(length = 64) String fingerprint;
    @Column(columnDefinition = "TEXT") String metadataJson;
    @Column(columnDefinition = "BYTEA") byte[] content;
    @Column(nullable = false) Instant queuedAt;
    boolean tombstone;
}
