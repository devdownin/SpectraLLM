package fr.spectra.controller;

import fr.spectra.persistence.ArticleCommentEntity;
import fr.spectra.service.ArticleCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Commentaires d'articles : ajout manuel, génération RAG, évaluation, export DPO.
 */
@RestController
@RequestMapping("/api/ged/documents")
@Tag(name = "Comments", description = "Commentaires d'articles avec génération RAG et export DPO")
public class ArticleCommentController {

    private final ArticleCommentService commentService;
    private final Path dpoExportPath;

    public ArticleCommentController(ArticleCommentService commentService,
                                    @Value("${spectra.dataset.dir:./data/dataset}") String datasetDir) {
        this.commentService = commentService;
        this.dpoExportPath = Path.of(datasetDir).resolve("comments_dpo.jsonl");
    }

    // ── Lecture ───────────────────────────────────────────────────────────────

    @GetMapping("/{sha256}/comments")
    @Operation(summary = "Liste tous les commentaires d'un document")
    public ResponseEntity<List<Map<String, Object>>> getComments(@PathVariable String sha256) {
        List<Map<String, Object>> result = commentService.getComments(sha256)
                .stream().map(this::toMap).toList();
        return ResponseEntity.ok(result);
    }

    // ── Ajout ─────────────────────────────────────────────────────────────────

    @PostMapping("/{sha256}/comments")
    @Operation(summary = "Ajoute un commentaire humain ou génère un commentaire IA via RAG")
    public ResponseEntity<Map<String, Object>> addComment(
            @PathVariable String sha256,
            @RequestBody CommentRequest body,
            @RequestParam(defaultValue = "ui") String actor) {
        try {
            ArticleCommentEntity saved;
            if (body.generate()) {
                saved = commentService.generateAiComment(sha256, body.content(), actor);
            } else {
                saved = commentService.addHumanComment(sha256, body.content(), actor);
            }
            return ResponseEntity.ok(toMap(saved));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Évaluation ────────────────────────────────────────────────────────────

    @PatchMapping("/{sha256}/comments/{id}/rating")
    @Operation(summary = "Évalue un commentaire IA (APPROVED/REJECTED) pour constituer les paires DPO")
    public ResponseEntity<Map<String, Object>> rateComment(
            @PathVariable String sha256,
            @PathVariable Long id,
            @RequestParam String rating) {
        try {
            ArticleCommentEntity.Rating r = ArticleCommentEntity.Rating.valueOf(rating.toUpperCase());
            ArticleCommentEntity updated = commentService.rateComment(id, r);
            return ResponseEntity.ok(toMap(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Rating invalide. Valeurs possibles : NONE, APPROVED, REJECTED"));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Suppression ───────────────────────────────────────────────────────────

    @DeleteMapping("/{sha256}/comments/{id}")
    @Operation(summary = "Supprime un commentaire")
    public ResponseEntity<Void> deleteComment(@PathVariable String sha256, @PathVariable Long id) {
        try {
            commentService.deleteComment(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Export DPO ────────────────────────────────────────────────────────────
    // Mapped on a separate path to avoid collision with /{sha256}/comments routes.

    @PostMapping("/export/comments-dpo")
    @Operation(summary = "Exporte les commentaires évalués sous forme de paires DPO pour le fine-tuning")
    public ResponseEntity<Map<String, Object>> exportDpo() {
        try {
            int count = commentService.exportDpoPairs(dpoExportPath);
            return ResponseEntity.ok(Map.of(
                    "pairs", count,
                    "file", dpoExportPath.toString(),
                    "exportedAt", Instant.now().toString()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> toMap(ArticleCommentEntity c) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id",          c.getId());
        m.put("sha256",      c.getDocumentSha256());
        m.put("content",     c.getContent());
        m.put("author",      c.getAuthor());
        m.put("type",        c.getCommentType().name());
        m.put("rating",      c.getRating().name());
        m.put("focus",       c.getFocus() != null ? c.getFocus() : "");
        m.put("createdAt",   c.getCreatedAt().toString());
        m.put("updatedAt",   c.getUpdatedAt().toString());
        return m;
    }

    record CommentRequest(
            /** Texte du commentaire humain, ou focus pour la génération IA. */
            String content,
            /** Si true, génère un commentaire IA via RAG au lieu d'enregistrer content tel quel. */
            boolean generate
    ) {}
}
