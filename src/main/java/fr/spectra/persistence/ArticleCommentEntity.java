package fr.spectra.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "article_comments")
public class ArticleCommentEntity {

    public enum CommentType { HUMAN, AI_GENERATED }

    /** Rating used to build DPO training pairs: APPROVED → chosen, REJECTED → rejected. */
    public enum Rating { NONE, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String documentSha256;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    private String author;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommentType commentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Rating rating;

    /** The RAG context used during generation (serialised chunk texts), null for human comments. */
    @Column(columnDefinition = "TEXT")
    private String ragContext;

    /** The user-supplied focus/intent prompt, null for human comments. */
    @Column(columnDefinition = "TEXT")
    private String focus;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ArticleCommentEntity() {}

    public ArticleCommentEntity(String documentSha256, String content, String author,
                                CommentType commentType, String ragContext, String focus) {
        this.documentSha256 = documentSha256;
        this.content = content;
        this.author = author;
        this.commentType = commentType;
        this.ragContext = ragContext;
        this.focus = focus;
        this.rating = Rating.NONE;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Long getId()                   { return id; }
    public String getDocumentSha256()     { return documentSha256; }
    public String getContent()            { return content; }
    public String getAuthor()             { return author; }
    public CommentType getCommentType()   { return commentType; }
    public Rating getRating()             { return rating; }
    public String getRagContext()         { return ragContext; }
    public String getFocus()              { return focus; }
    public Instant getCreatedAt()         { return createdAt; }
    public Instant getUpdatedAt()         { return updatedAt; }

    // ── Mutators ──────────────────────────────────────────────────────────────

    public void setContent(String content) {
        this.content = content;
        this.updatedAt = Instant.now();
    }

    public void setRating(Rating rating) {
        this.rating = rating;
        this.updatedAt = Instant.now();
    }
}
