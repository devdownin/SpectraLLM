package fr.spectra.controller;

import fr.spectra.dto.GedDocumentFilter;
import fr.spectra.persistence.AuditLogEntity;
import fr.spectra.persistence.DocumentModelLinkEntity;
import fr.spectra.persistence.IngestedFileEntity;
import fr.spectra.service.GedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Interface GED : fiche document, filtrage paginé, vue par modèle,
 * actions groupées, suppression synchronisée, statistiques.
 */
@RestController
@RequestMapping("/api/ged")
@Tag(name = "GED", description = "Gestion Électronique de Documents")
public class GedController {

    private final GedService gedService;

    public GedController(GedService gedService) {
        this.gedService = gedService;
    }

    // ── Amélioration 2 — Filtrage combiné + pagination ────────────────────────

    @GetMapping("/documents")
    @Operation(summary = "Liste les documents GED avec filtrage combiné et pagination")
    public Map<String, Object> listAll(
            @RequestParam(required = false) String lifecycle,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String collection,
            @RequestParam(required = false) Double minQuality,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        IngestedFileEntity.Lifecycle lc = lifecycle != null
                ? IngestedFileEntity.Lifecycle.valueOf(lifecycle.toUpperCase()) : null;
        Instant fromInst = from != null ? Instant.parse(from) : null;
        Instant toInst   = to   != null ? Instant.parse(to)   : null;

        GedDocumentFilter filter = new GedDocumentFilter(
                lc, tag, collection, minQuality, fromInst, toInst, page, size);
        Page<IngestedFileEntity> result = gedService.findFiltered(filter);

        return Map.of(
                "content",       result.getContent().stream().map(this::toSheet).toList(),
                "page",          result.getNumber(),
                "size",          result.getSize(),
                "totalElements", result.getTotalElements(),
                "totalPages",    result.getTotalPages()
        );
    }

    // ── Fiche document ────────────────────────────────────────────────────────

    @GetMapping("/documents/{sha256}")
    @Operation(summary = "Fiche complète d'un document (métadonnées + liens modèles + audit)")
    public ResponseEntity<Map<String, Object>> getDocument(@PathVariable String sha256) {
        return gedService.findById(sha256)
                .map(doc -> {
                    Map<String, Object> sheet = toSheet(doc);
                    sheet.put("modelLinks", gedService.getLinksForDocument(sha256).stream()
                            .map(l -> Map.of(
                                    "model",    l.getModelName(),
                                    "type",     l.getLinkType().name(),
                                    "linkedAt", l.getLinkedAt().toString()))
                            .toList());
                    sheet.put("auditTrail", gedService.getAuditTrail(sha256).stream()
                            .map(a -> Map.of(
                                    "action",    a.getAction().name(),
                                    "actor",     a.getActor(),
                                    "timestamp", a.getTimestamp().toString(),
                                    "details",   a.getDetails() != null ? a.getDetails() : ""))
                            .toList());
                    return ResponseEntity.ok(sheet);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Amélioration 3 — Suppression synchronisée ChromaDB + GED ─────────────

    @DeleteMapping("/documents/{sha256}")
    @Operation(summary = "Supprime un document de la GED et ses chunks ChromaDB associés")
    public ResponseEntity<Map<String, Object>> deleteDocument(
            @PathVariable String sha256,
            @RequestParam(defaultValue = "api") String actor) {
        try {
            Map<String, Object> result = gedService.deleteDocument(sha256, actor);
            return ResponseEntity.ok(result);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Cycle de vie (R2 + machine à états) ──────────────────────────────────

    @PutMapping("/documents/{sha256}/lifecycle")
    @Operation(summary = "Change le cycle de vie d'un document (transitions validées)")
    public ResponseEntity<Map<String, Object>> setLifecycle(
            @PathVariable String sha256,
            @RequestParam String lifecycle,
            @RequestParam(defaultValue = "api") String actor) {
        try {
            IngestedFileEntity.Lifecycle target =
                    IngestedFileEntity.Lifecycle.valueOf(lifecycle.toUpperCase());
            IngestedFileEntity doc = gedService.transitionLifecycle(sha256, target, actor);
            return ResponseEntity.ok(Map.of(
                    "sha256",    sha256,
                    "lifecycle", doc.getLifecycle().name()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Valeur invalide : " + lifecycle +
                    ". Valeurs possibles : INGESTED, QUALIFIED, TRAINED, ARCHIVED"));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Tags (R5) ─────────────────────────────────────────────────────────────

    @PostMapping("/documents/{sha256}/tags")
    @Operation(summary = "Ajoute des tags thématiques à un document")
    public ResponseEntity<Map<String, Object>> addTags(
            @PathVariable String sha256,
            @RequestBody List<String> tags,
            @RequestParam(defaultValue = "api") String actor) {
        try {
            IngestedFileEntity doc = gedService.addTags(sha256, tags, actor);
            return ResponseEntity.ok(Map.of("sha256", sha256, "tags", doc.getTags()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/documents/{sha256}/tags")
    @Operation(summary = "Supprime des tags d'un document")
    public ResponseEntity<Map<String, Object>> removeTags(
            @PathVariable String sha256,
            @RequestBody List<String> tags,
            @RequestParam(defaultValue = "api") String actor) {
        try {
            IngestedFileEntity doc = gedService.removeTags(sha256, tags, actor);
            return ResponseEntity.ok(Map.of("sha256", sha256, "tags", doc.getTags()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Liens modèle (R1) ────────────────────────────────────────────────────

    @PostMapping("/documents/{sha256}/models")
    @Operation(summary = "Associe un document à un modèle (TRAINED_ON ou EVALUATED_ON)")
    public ResponseEntity<Map<String, Object>> linkToModel(
            @PathVariable String sha256,
            @RequestParam String modelName,
            @RequestParam(defaultValue = "TRAINED_ON") String linkType,
            @RequestParam(defaultValue = "api") String actor) {
        try {
            DocumentModelLinkEntity.LinkType type =
                    DocumentModelLinkEntity.LinkType.valueOf(linkType.toUpperCase());
            DocumentModelLinkEntity link = gedService.linkToModel(sha256, modelName, type, actor);
            return ResponseEntity.ok(Map.of(
                    "documentSha256", sha256,
                    "modelName",      link.getModelName(),
                    "linkType",       link.getLinkType().name(),
                    "linkedAt",       link.getLinkedAt().toString()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "linkType invalide. Valeurs possibles : TRAINED_ON, EVALUATED_ON"));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/models/{modelName}/documents")
    @Operation(summary = "Liste les documents associés à un modèle donné")
    public List<Map<String, Object>> getDocumentsByModel(@PathVariable String modelName) {
        return gedService.getDocumentsByModel(modelName).stream().map(this::toSheet).toList();
    }

    // ── Audit trail (R6) ──────────────────────────────────────────────────────

    @GetMapping("/documents/{sha256}/audit")
    @Operation(summary = "Retourne l'audit trail complet d'un document")
    public ResponseEntity<List<Map<String, Object>>> getAudit(@PathVariable String sha256) {
        if (gedService.findById(sha256).isEmpty()) return ResponseEntity.notFound().build();
        List<Map<String, Object>> trail = gedService.getAuditTrail(sha256).stream()
                .map(a -> Map.<String, Object>of(
                        "action",    a.getAction().name(),
                        "actor",     a.getActor(),
                        "timestamp", a.getTimestamp().toString(),
                        "details",   a.getDetails() != null ? a.getDetails() : ""))
                .toList();
        return ResponseEntity.ok(trail);
    }

    // ── Amélioration 5 — Statistiques GED ────────────────────────────────────

    @GetMapping("/stats")
    @Operation(summary = "Statistiques GED : répartition lifecycle, qualité, top tags, total chunks")
    public Map<String, Object> stats() {
        return gedService.stats();
    }

    // ── Actions groupées ──────────────────────────────────────────────────────

    @PostMapping("/documents/bulk/lifecycle")
    @Operation(summary = "Change le cycle de vie d'une liste de documents")
    public Map<String, Object> bulkLifecycle(
            @RequestBody List<String> sha256List,
            @RequestParam String lifecycle,
            @RequestParam(defaultValue = "api") String actor) {
        IngestedFileEntity.Lifecycle target =
                IngestedFileEntity.Lifecycle.valueOf(lifecycle.toUpperCase());
        int success = 0;
        List<String> errors = new java.util.ArrayList<>();
        for (String sha256 : sha256List) {
            try {
                gedService.transitionLifecycle(sha256, target, actor);
                success++;
            } catch (NoSuchElementException e) {
                errors.add(sha256 + ": introuvable");
            } catch (IllegalStateException e) {
                errors.add(sha256 + ": " + e.getMessage());
            } catch (Exception e) {
                errors.add(sha256 + ": " + e.getMessage());
            }
        }
        return Map.of("updated", success, "errors", errors);
    }

    @PostMapping("/documents/bulk/tags")
    @Operation(summary = "Ajoute des tags à une liste de documents")
    public Map<String, Object> bulkAddTags(
            @RequestBody BulkTagRequest request,
            @RequestParam(defaultValue = "api") String actor) {
        int success = 0;
        List<String> errors = new java.util.ArrayList<>();
        for (String sha256 : request.sha256List()) {
            try {
                gedService.addTags(sha256, request.tags(), actor);
                success++;
            } catch (NoSuchElementException e) {
                errors.add(sha256 + ": introuvable");
            }
        }
        return Map.of("updated", success, "errors", errors);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private Map<String, Object> toSheet(IngestedFileEntity doc) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("sha256",         doc.getSha256());
        m.put("fileName",       doc.getFileName());
        m.put("format",         doc.getFormat());
        m.put("ingestedAt",     doc.getIngestedAt() != null ? doc.getIngestedAt().toString() : null);
        m.put("lifecycle",      doc.getLifecycle() != null ? doc.getLifecycle().name() : "INGESTED");
        m.put("version",        doc.getVersion());
        m.put("tags",           doc.getTags());
        m.put("qualityScore",   doc.getQualityScore());
        m.put("chunksCreated",  doc.getChunksCreated());
        m.put("collectionName", doc.getCollectionName());
        return m;
    }

    record BulkTagRequest(List<String> sha256List, List<String> tags) {}
}
