package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.dto.GedDocumentFilter;
import fr.spectra.persistence.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * R1–R7 — Service GED central.
 * Gère le cycle de vie, les tags, les liens document↔modèle, l'audit trail,
 * l'archivage structuré et le score de qualité.
 */
@Service
public class GedService {

    private static final Logger log = LoggerFactory.getLogger(GedService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TOP_TAGS_TTL_MS = 30_000;

    // Cache TTL des top-tags pour /api/ged/stats (évite de reparser tout le JSON à chaque appel).
    private volatile List<Map<String, Object>> cachedTopTags = List.of();
    private volatile long topTagsCachedAt = 0L;

    private final IngestedFileRepository      fileRepo;
    private final DocumentModelLinkRepository linkRepo;
    private final AuditLogRepository          auditRepo;
    private final ChromaDbClient              chromaDbClient;
    private final FtsService                  ftsService;
    private final Path                        archiveRoot;

    // Auto-injection pour invoquer les méthodes @Transactional via le proxy Spring.
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private GedService self;

    public GedService(IngestedFileRepository fileRepo,
                      DocumentModelLinkRepository linkRepo,
                      AuditLogRepository auditRepo,
                      ChromaDbClient chromaDbClient,
                      FtsService ftsService,
                      @Value("${spectra.ged.archive-dir:./data/archive}") String archiveDir) {
        this.fileRepo       = fileRepo;
        this.linkRepo       = linkRepo;
        this.auditRepo      = auditRepo;
        this.chromaDbClient = chromaDbClient;
        this.ftsService     = ftsService;
        this.archiveRoot    = Path.of(archiveDir);
    }

    // ── R2 — Cycle de vie ────────────────────────────────────────────────────

    @Transactional
    public IngestedFileEntity transitionLifecycle(String sha256,
                                                   IngestedFileEntity.Lifecycle target,
                                                   String actor) {
        IngestedFileEntity doc = requireDoc(sha256);
        IngestedFileEntity.Lifecycle previous = doc.getLifecycle();
        previous.validateTransition(target);   // machine à états
        doc.setLifecycle(target);
        fileRepo.save(doc);

        audit(sha256, AuditLogEntity.Action.LIFECYCLE_CHANGED, actor,
              Map.of("from", previous.name(), "to", target.name()));

        if (target == IngestedFileEntity.Lifecycle.ARCHIVED) {
            writeManifest(doc); // R3
        }
        return doc;
    }

    // ── R3 — Archivage structuré avec manifest.json ──────────────────────────

    public void writeManifest(IngestedFileEntity doc) {
        Path dir = archiveRoot.resolve(doc.getSha256());
        try {
            Files.createDirectories(dir);
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("sha256", doc.getSha256());
            manifest.put("fileName", doc.getFileName());
            manifest.put("format", doc.getFormat());
            manifest.put("ingestedAt", doc.getIngestedAt().toString());
            manifest.put("lifecycle", doc.getLifecycle().name());
            manifest.put("version", doc.getVersion());
            manifest.put("tags", doc.getTags());
            manifest.put("qualityScore", doc.getQualityScore());
            manifest.put("collectionName", doc.getCollectionName());
            manifest.put("chunksCreated", doc.getChunksCreated());
            manifest.put("archivedAt", Instant.now().toString());
            List<Map<String, String>> links = linkRepo.findByDocumentSha256(doc.getSha256())
                    .stream()
                    .map(l -> Map.of("model", l.getModelName(),
                                     "type", l.getLinkType().name(),
                                     "at", l.getLinkedAt().toString()))
                    .toList();
            manifest.put("modelLinks", links);

            MAPPER.writerWithDefaultPrettyPrinter()
                  .writeValue(dir.resolve("manifest.json").toFile(), manifest);
            log.info("Manifest GED écrit : {}", dir.resolve("manifest.json"));
        } catch (IOException e) {
            log.warn("Impossible d'écrire le manifest pour {} : {}", doc.getSha256(), e.getMessage());
        }
    }

    // ── R4 — Versioning ──────────────────────────────────────────────────────

    @Transactional
    public IngestedFileEntity incrementVersion(String sha256, String actor) {
        IngestedFileEntity doc = requireDoc(sha256);
        int newVersion = doc.getVersion() + 1;
        doc.setVersion(newVersion);
        doc.setLifecycle(IngestedFileEntity.Lifecycle.INGESTED);
        fileRepo.save(doc);
        audit(sha256, AuditLogEntity.Action.RE_INGESTED, actor,
              Map.of("newVersion", String.valueOf(newVersion)));
        return doc;
    }

    // ── R5 — Tags thématiques ────────────────────────────────────────────────

    @Transactional
    public IngestedFileEntity addTags(String sha256, List<String> newTags, String actor) {
        IngestedFileEntity doc = requireDoc(sha256);
        List<String> merged = new ArrayList<>(doc.getTags());
        newTags.stream()
               .map(String::trim)
               .filter(t -> !t.isBlank() && !merged.contains(t))
               .forEach(merged::add);
        doc.setTags(merged);
        fileRepo.save(doc);
        audit(sha256, AuditLogEntity.Action.TAGGED, actor, Map.of("added", String.join(",", newTags)));
        return doc;
    }

    @Transactional
    public IngestedFileEntity removeTags(String sha256, List<String> removedTags, String actor) {
        IngestedFileEntity doc = requireDoc(sha256);
        List<String> updated = new ArrayList<>(doc.getTags());
        updated.removeAll(removedTags);
        doc.setTags(updated);
        fileRepo.save(doc);
        audit(sha256, AuditLogEntity.Action.UNTAGGED, actor,
              Map.of("removed", String.join(",", removedTags)));
        return doc;
    }

    // ── R1 — Liens document ↔ modèle ─────────────────────────────────────────

    @Transactional
    public DocumentModelLinkEntity linkToModel(String sha256, String modelName,
                                               DocumentModelLinkEntity.LinkType linkType,
                                               String actor) {
        if (linkRepo.existsByDocumentSha256AndModelNameAndLinkType(sha256, modelName, linkType)) {
            return linkRepo.findByDocumentSha256(sha256).stream()
                    .filter(l -> l.getModelName().equals(modelName) && l.getLinkType() == linkType)
                    .findFirst().orElseThrow();
        }
        DocumentModelLinkEntity link =
                new DocumentModelLinkEntity(sha256, modelName, linkType, Instant.now());
        linkRepo.save(link);
        audit(sha256, AuditLogEntity.Action.LINKED_TO_MODEL, actor,
              Map.of("model", modelName, "type", linkType.name()));
        return link;
    }

    public List<DocumentModelLinkEntity> getLinksForDocument(String sha256) {
        return linkRepo.findByDocumentSha256(sha256);
    }

    public List<IngestedFileEntity> getDocumentsByModel(String modelName) {
        List<String> sha256s = linkRepo.findByModelName(modelName)
                .stream().map(DocumentModelLinkEntity::getDocumentSha256).distinct().toList();
        if (sha256s.isEmpty()) return List.of();
        return fileRepo.findAllById(sha256s);
    }

    // ── R6 — Audit trail ─────────────────────────────────────────────────────

    public List<AuditLogEntity> getAuditTrail(String sha256) {
        return auditRepo.findByDocumentSha256OrderByTimestampDesc(sha256);
    }

    /** Enregistre une entrée d'audit (appelé en interne et par IngestionService). */
    public void audit(String sha256, AuditLogEntity.Action action,
                      String actor, Map<String, String> details) {
        String detailsJson = null;
        if (details != null && !details.isEmpty()) {
            try { detailsJson = MAPPER.writeValueAsString(details); }
            catch (Exception ignored) { detailsJson = details.toString(); }
        }
        auditRepo.save(new AuditLogEntity(sha256, action,
                actor != null ? actor : "system", Instant.now(), detailsJson));
    }

    // ── R7 — Score de qualité ─────────────────────────────────────────────────

    /**
     * Score de qualité 0.0–1.0.
     * Heuristique : chunks créés (poids 70 %), bonus format enrichi (30 %).
     *
     * @param chunksCreated nombre de chunks produits
     * @param format        extension du fichier (PDF, DOCX, etc.)
     */
    public static double computeQualityScore(int chunksCreated, String format) {
        double chunkScore = Math.min(1.0, chunksCreated / 20.0); // 20 chunks = score max
        double formatBonus = switch (format != null ? format.toUpperCase() : "") {
            case "PDF"  -> 0.2;
            case "DOCX" -> 0.15;
            case "HTML" -> 0.1;
            case "JSON" -> 0.1;
            case "XML" -> 0.1;
            default     -> 0.0;
        };
        return Math.min(1.0, chunkScore * 0.7 + formatBonus * 0.3 + (chunksCreated > 0 ? 0.1 : 0.0));
    }

    // ── Amélioration 2 — Filtrage combiné + pagination ───────────────────────

    public Page<IngestedFileEntity> findFiltered(GedDocumentFilter f) {
        Specification<IngestedFileEntity> spec = Specification.where((root, query, cb) -> cb.conjunction());

        if (f.lifecycle() != null) {
            spec = spec.and((root, q, cb) ->
                    cb.equal(root.get("lifecycle"), f.lifecycle()));
        }
        if (f.collection() != null && !f.collection().isBlank()) {
            spec = spec.and((root, q, cb) ->
                    cb.equal(root.get("collectionName"), f.collection()));
        }
        if (f.minQuality() != null) {
            spec = spec.and((root, q, cb) ->
                    cb.greaterThanOrEqualTo(root.get("qualityScore"), f.minQuality()));
        }
        if (f.from() != null) {
            spec = spec.and((root, q, cb) ->
                    cb.greaterThanOrEqualTo(root.get("ingestedAt"), f.from()));
        }
        if (f.to() != null) {
            spec = spec.and((root, q, cb) ->
                    cb.lessThanOrEqualTo(root.get("ingestedAt"), f.to()));
        }
        // Tag : recherche dans le JSON sérialisé (LIKE '%"tag"%')
        if (f.tag() != null && !f.tag().isBlank()) {
            String pattern = "%\"" + f.tag().replace("\"", "") + "\"%";
            spec = spec.and((root, q, cb) ->
                    cb.like(root.get("tags"), pattern));
        }
        // Recherche par nom de fichier (insensible à la casse) — côté serveur pour couvrir
        // l'ensemble du corpus, pas seulement la page chargée par l'UI.
        if (f.q() != null && !f.q().isBlank()) {
            String like = "%" + f.q().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((root, query, cb) ->
                    cb.like(cb.lower(root.get("fileName")), like, '\\'));
        }

        PageRequest page = PageRequest.of(f.page(), f.size(),
                Sort.by(Sort.Direction.DESC, "ingestedAt"));
        return fileRepo.findAll(spec, page);
    }

    // ── Amélioration 3 — Suppression synchronisée ChromaDB + GED ────────────

    /**
     * Supprime un document : d'abord les données DB autoritatives (transaction qui commit),
     * puis le nettoyage externe ChromaDB/FTS en best-effort. L'ordre garantit qu'un rollback
     * DB n'orpheline jamais l'état externe, et le nettoyage synchrone permet de renvoyer le
     * nombre réel de chunks supprimés (contrairement à l'ancien afterCommit qui renvoyait 0).
     */
    public Map<String, Object> deleteDocument(String sha256, String actor) {
        // 1. Suppressions DB autoritatives — la transaction commit au retour de cet appel proxy.
        //    Fallback sur `this` quand self n'est pas injecté (tests unitaires hors contexte Spring).
        DeleteInfo info = (self != null ? self : this).deleteDocumentDb(sha256);

        // 2. Nettoyage externe (ChromaDB + FTS) best-effort, APRÈS le commit DB.
        int chunksDeleted = 0;
        if (info.collection() != null && !info.collection().isBlank()) {
            try {
                String collectionId = chromaDbClient.getOrCreateCollection(info.collection());
                chunksDeleted = chromaDbClient.deleteBySource(collectionId, info.fileName());
                ftsService.removeBySource(info.fileName(), info.collection());
                log.info("Document {} : {} chunks ChromaDB supprimés", sha256, chunksDeleted);
            } catch (Exception e) {
                log.warn("Nettoyage ChromaDB/FTS échoué pour {} : {}", sha256, e.getMessage());
            }
        }

        log.info("Document {} supprimé de la GED", sha256);
        return Map.of(
                "sha256", sha256,
                "fileName", info.fileName(),
                "chunksDeleted", chunksDeleted,
                "actor", actor != null ? actor : "api"
        );
    }

    /** Métadonnées nécessaires au nettoyage externe après la suppression DB. */
    public record DeleteInfo(String collection, String fileName) {}

    /** Suppressions DB autoritatives dans une transaction dédiée (invoquée via {@link #self}). */
    @Transactional
    public DeleteInfo deleteDocumentDb(String sha256) {
        IngestedFileEntity doc = requireDoc(sha256);
        String collection = doc.getCollectionName();
        String fileName = doc.getFileName();
        linkRepo.deleteAll(linkRepo.findByDocumentSha256(sha256));
        auditRepo.deleteAll(auditRepo.findByDocumentSha256OrderByTimestampDesc(sha256));
        fileRepo.delete(doc);
        return new DeleteInfo(collection, fileName);
    }

    // ── Amélioration 5 — Statistiques GED ───────────────────────────────────

    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Comptage par lifecycle
        Map<String, Long> byLifecycle = new LinkedHashMap<>();
        for (Object[] row : fileRepo.countByLifecycle()) {
            byLifecycle.put(row[0].toString(), (Long) row[1]);
        }
        result.put("byLifecycle", byLifecycle);
        result.put("total", byLifecycle.values().stream().mapToLong(Long::longValue).sum());

        // Score qualité
        Double avg = fileRepo.avgQualityScore();
        result.put("avgQualityScore", avg != null ? Math.round(avg * 100.0) / 100.0 : null);

        // Distribution qualité — DB-level counts, pas de chargement en mémoire
        result.put("qualityDistribution", Map.of(
                "0.00-0.25", nullToZero(fileRepo.countQualityQ0()),
                "0.25-0.50", nullToZero(fileRepo.countQualityQ1()),
                "0.50-0.75", nullToZero(fileRepo.countQualityQ2()),
                "0.75-1.00", nullToZero(fileRepo.countQualityQ3())
        ));

        // Top 10 tags — reparse coûteux mis en cache avec un TTL court.
        result.put("topTags", topTags());

        // Total chunks
        Long totalChunks = fileRepo.sumChunks();
        result.put("totalChunks", totalChunks != null ? totalChunks : 0L);

        return result;
    }

    /**
     * Calcule le top 10 des tags (seule la colonne tags est chargée), mis en cache
     * {@value #TOP_TAGS_TTL_MS} ms pour éviter de reparser tout le JSON à chaque appel
     * de {@code /api/ged/stats}.
     */
    private List<Map<String, Object>> topTags() {
        long now = System.currentTimeMillis();
        if (now - topTagsCachedAt < TOP_TAGS_TTL_MS) return cachedTopTags;

        Map<String, Long> tagCounts = new HashMap<>();
        fileRepo.findAllTagsJson().forEach(tagsJson -> {
            if (tagsJson != null && !tagsJson.isBlank()) {
                try {
                    @SuppressWarnings("unchecked")
                    List<String> tags = MAPPER.readValue(tagsJson, List.class);
                    tags.forEach(t -> tagCounts.merge(t, 1L, Long::sum));
                } catch (Exception ignored) {}
            }
        });
        List<Map<String, Object>> topTags = tagCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> Map.<String, Object>of("tag", e.getKey(), "count", e.getValue()))
                .toList();
        cachedTopTags = topTags;
        topTagsCachedAt = now;
        return topTags;
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private static long nullToZero(Long v) { return v != null ? v : 0L; }

    // ── Queries ──────────────────────────────────────────────────────────────

    public Optional<IngestedFileEntity> findById(String sha256) {
        return fileRepo.findById(sha256);
    }

    public List<IngestedFileEntity> findAll() {
        return fileRepo.findAllByOrderByIngestedAtDesc();
    }

    public List<IngestedFileEntity> findByLifecycle(IngestedFileEntity.Lifecycle lifecycle) {
        return fileRepo.findByLifecycleOrderByIngestedAtDesc(lifecycle);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private IngestedFileEntity requireDoc(String sha256) {
        return fileRepo.findById(sha256)
                .orElseThrow(() -> new NoSuchElementException("Document introuvable : " + sha256));
    }
}
