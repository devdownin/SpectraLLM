package fr.spectra.sources;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="source_runs", indexes=@Index(name="idx_source_runs_instance", columnList="source_id,started_at"))
public class SourceRun {
    @Id public String id;
    @Column(name="source_id", nullable=false) public String sourceId;
    @Enumerated(EnumType.STRING) public SourceModels.Status status;
    public int scanned;
    public int indexed;
    public int unchanged;
    public int deleted;
    public int failed;
    public boolean discoveryFinished;
    public boolean discoveryComplete;
    public Instant startedAt;
    public Instant completedAt;
    @Column(length=1000) public String error;
    public SourceRun() {}
    public SourceModels.Run toDto() {
        return new SourceModels.Run(id, sourceId, status, scanned, indexed, unchanged, deleted, failed, startedAt, completedAt, error);
    }
}
