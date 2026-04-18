package fr.spectra.persistence;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "ingested_files")
public class IngestedFileEntity {

    /** Cycle de vie GED (R2) avec machine à états. */
    public enum Lifecycle {
        INGESTED, QUALIFIED, TRAINED, ARCHIVED;

        private static final java.util.Map<Lifecycle, java.util.Set<Lifecycle>> ALLOWED =
                java.util.Map.of(
                        INGESTED,  java.util.Set.of(QUALIFIED, ARCHIVED),
                        QUALIFIED, java.util.Set.of(TRAINED, INGESTED, ARCHIVED),
                        TRAINED,   java.util.Set.of(ARCHIVED, QUALIFIED),
                        ARCHIVED,  java.util.Set.of(INGESTED)
                );

        /**
         * Vérifie que la transition {@code this → target} est autorisée.
         * @throws IllegalStateException si la transition est invalide.
         */
        public void validateTransition(Lifecycle target) {
            if (!ALLOWED.get(this).contains(target)) {
                throw new IllegalStateException(
                        "Transition interdite : " + this.name() + " → " + target.name() +
                        ". Transitions autorisées depuis " + this.name() + " : " + ALLOWED.get(this));
            }
        }
    }

    @Id
    private String sha256;

    private String fileName;
    private String format;
    private Instant ingestedAt;
    private int chunksCreated;

    // R2 — cycle de vie
    @Enumerated(EnumType.STRING)
    private Lifecycle lifecycle = Lifecycle.INGESTED;

    // R4 — versioning
    private int version = 1;

    // R5 — tags thématiques
    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> tags = new ArrayList<>();

    // R7 — score de qualité d'ingestion (0.0 – 1.0)
    private Double qualityScore;

    // collection ChromaDB cible
    private String collectionName;

    protected IngestedFileEntity() {}

    public IngestedFileEntity(String sha256, String fileName, String format,
                              Instant ingestedAt, int chunksCreated,
                              String collectionName, Double qualityScore) {
        this.sha256 = sha256;
        this.fileName = fileName;
        this.format = format;
        this.ingestedAt = ingestedAt;
        this.chunksCreated = chunksCreated;
        this.collectionName = collectionName;
        this.qualityScore = qualityScore;
        this.lifecycle = Lifecycle.INGESTED;
        this.version = 1;
        this.tags = new ArrayList<>();
    }

    /** Compatibilité ascendante : pas de collection ni de score. */
    public IngestedFileEntity(String sha256, String fileName, String format,
                              Instant ingestedAt, int chunksCreated) {
        this(sha256, fileName, format, ingestedAt, chunksCreated, null, null);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getSha256()        { return sha256; }
    public String getFileName()      { return fileName; }
    public String getFormat()        { return format; }
    public Instant getIngestedAt()   { return ingestedAt; }
    public int getChunksCreated()    { return chunksCreated; }
    public Lifecycle getLifecycle()  { return lifecycle; }
    public int getVersion()          { return version; }
    public List<String> getTags()    { return tags; }
    public Double getQualityScore()  { return qualityScore; }
    public String getCollectionName(){ return collectionName; }

    // ── Mutators ──────────────────────────────────────────────────────────────

    public void setLifecycle(Lifecycle lifecycle) { this.lifecycle = lifecycle; }
    public void setVersion(int version)           { this.version = version; }
    public void setTags(List<String> tags)        { this.tags = tags != null ? tags : new ArrayList<>(); }
    public void setQualityScore(Double score)     { this.qualityScore = score; }
    public void setCollectionName(String col)     { this.collectionName = col; }
}
