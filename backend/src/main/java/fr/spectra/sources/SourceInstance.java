package fr.spectra.sources;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="source_instances")
public class SourceInstance {
    @Id public String id;
    @Version public long lockVersion;
    public String name;
    @Enumerated(EnumType.STRING) public SourceModels.Type type;
    @Column(name="collection_name") public String collection;
    public boolean enabled;
    public int intervalMinutes;
    @Column(columnDefinition="TEXT") public String configJson;
    @Enumerated(EnumType.STRING) public SourceModels.Status status;
    public Instant lastSuccessAt;
    public Instant nextSyncAt;
    @Column(length=1000) public String lastError;
    public SourceInstance() {}
}
