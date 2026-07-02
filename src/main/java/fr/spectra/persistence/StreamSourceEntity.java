package fr.spectra.persistence;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * État d'une source alimentée par le flux Kafka, keyée par identité métier
 * ({@code sourceKey = kafka://<topic>/<key>}).
 *
 * <p>Contrairement à {@link IngestedFileEntity} (keyée par SHA-256 de contenu, pensée pour
 * des documents immuables), cette table suit des <b>données vivantes</b> : une même clé métier
 * est mise à jour au fil du temps. Le {@code contentHash} permet une idempotence bon marché
 * (rejeu at-least-once → pas de réindexation si le contenu n'a pas changé), et
 * {@code lastUpdatedAt} alimente la politique de rétention.</p>
 */
@Entity
@Table(name = "kafka_stream_source", indexes = {
        @Index(name = "idx_kafka_stream_updated_at", columnList = "last_updated_at"),
        @Index(name = "idx_kafka_stream_collection", columnList = "collection")
})
public class StreamSourceEntity {

    @Id
    @Column(name = "source_key", length = 512)
    private String sourceKey;

    @Column(name = "collection")
    private String collection;

    /** SHA-256 hex du texte nettoyé de la dernière version indexée (idempotence). */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "chunk_count")
    private int chunkCount;

    /** Incrémentée à chaque upsert effectif (contenu changé). */
    @Column(name = "version")
    private long version;

    @Column(name = "first_seen_at")
    private Instant firstSeenAt;

    @Column(name = "last_updated_at")
    private Instant lastUpdatedAt;

    /** Référence topic/partition/offset du dernier message traité (diagnostic / replay). */
    @Column(name = "last_offset_ref", length = 512)
    private String lastOffsetRef;

    protected StreamSourceEntity() {
    }

    public StreamSourceEntity(String sourceKey, String collection) {
        this.sourceKey = sourceKey;
        this.collection = collection;
        this.version = 0;
        this.firstSeenAt = Instant.now();
    }

    public String getSourceKey() { return sourceKey; }
    public String getCollection() { return collection; }
    public void setCollection(String collection) { this.collection = collection; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(Instant lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }
    public String getLastOffsetRef() { return lastOffsetRef; }
    public void setLastOffsetRef(String lastOffsetRef) { this.lastOffsetRef = lastOffsetRef; }
}
