package fr.spectra.service;

import fr.spectra.persistence.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires de GedService — cycle de vie, tags, liens modèle, audit trail,
 * score qualité, archivage manifest.
 * Tous les dépôts sont mockés sauf le répertoire d'archive (TempDir).
 */
class GedServiceTest {

    @TempDir
    Path tempArchive;

    private IngestedFileRepository    fileRepo;
    private DocumentModelLinkRepository linkRepo;
    private AuditLogRepository         auditRepo;
    private GedService                 ged;

    @BeforeEach
    void setUp() {
        fileRepo  = mock(IngestedFileRepository.class);
        linkRepo  = mock(DocumentModelLinkRepository.class);
        auditRepo = mock(AuditLogRepository.class);
        ged = new GedService(fileRepo, linkRepo, auditRepo,
                mock(ChromaDbClient.class), mock(FtsService.class),
                tempArchive.toString());
        // findAll stub par défaut (pour stats)
        when(fileRepo.findAllByOrderByIngestedAtDesc()).thenReturn(List.of());
        when(fileRepo.countByLifecycle()).thenReturn(List.of());
        when(fileRepo.avgQualityScore()).thenReturn(null);
        when(fileRepo.sumChunks()).thenReturn(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // R2 — Cycle de vie
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void transitionLifecycle_changesLifecycleField() {
        IngestedFileEntity doc = entity("abc123");
        when(fileRepo.findById("abc123")).thenReturn(Optional.of(doc));
        when(fileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IngestedFileEntity updated = ged.transitionLifecycle("abc123",
                IngestedFileEntity.Lifecycle.QUALIFIED, "user");

        assertThat(updated.getLifecycle()).isEqualTo(IngestedFileEntity.Lifecycle.QUALIFIED);
    }

    @Test
    void transitionLifecycle_savesDocument() {
        IngestedFileEntity doc = entity("abc123");
        when(fileRepo.findById("abc123")).thenReturn(Optional.of(doc));
        when(fileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ged.transitionLifecycle("abc123", IngestedFileEntity.Lifecycle.QUALIFIED, "user");

        verify(fileRepo).save(doc);
    }

    @Test
    void transitionLifecycle_writesAuditEntry() {
        IngestedFileEntity doc = entity("abc123");
        when(fileRepo.findById("abc123")).thenReturn(Optional.of(doc));
        when(fileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ged.transitionLifecycle("abc123", IngestedFileEntity.Lifecycle.QUALIFIED, "system");

        verify(auditRepo).save(argThat(a -> a.getAction() == AuditLogEntity.Action.LIFECYCLE_CHANGED
                && "abc123".equals(a.getDocumentSha256())));
    }

    @Test
    void transitionLifecycle_toArchived_writesManifest() {
        IngestedFileEntity doc = entity("deadbeef");
        when(fileRepo.findById("deadbeef")).thenReturn(Optional.of(doc));
        when(fileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(linkRepo.findByDocumentSha256("deadbeef")).thenReturn(List.of());

        ged.transitionLifecycle("deadbeef", IngestedFileEntity.Lifecycle.ARCHIVED, "system");

        Path manifest = tempArchive.resolve("deadbeef").resolve("manifest.json");
        assertThat(manifest).exists();
    }

    @Test
    void transitionLifecycle_unknownSha256_throwsNoSuchElement() {
        when(fileRepo.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                ged.transitionLifecycle("unknown", IngestedFileEntity.Lifecycle.QUALIFIED, "u"))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // R3 — Manifest JSON
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void writeManifest_createsManifestFile() {
        IngestedFileEntity doc = entity("cafe1234");
        when(linkRepo.findByDocumentSha256("cafe1234")).thenReturn(List.of());

        ged.writeManifest(doc);

        Path manifest = tempArchive.resolve("cafe1234").resolve("manifest.json");
        assertThat(manifest).exists();
        assertThat(manifest.toFile().length()).isGreaterThan(0L);
    }

    @Test
    void writeManifest_containsExpectedFields() throws Exception {
        IngestedFileEntity doc = entity("f00dba5e");
        doc.setTags(List.of("kafka", "xml"));
        when(linkRepo.findByDocumentSha256("f00dba5e")).thenReturn(List.of());

        ged.writeManifest(doc);

        String content = java.nio.file.Files.readString(
                tempArchive.resolve("f00dba5e").resolve("manifest.json"));
        assertThat(content).contains("\"sha256\"");
        assertThat(content).contains("\"fileName\"");
        assertThat(content).contains("\"lifecycle\"");
        assertThat(content).contains("\"kafka\"");
        assertThat(content).contains("\"xml\"");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // R4 — Versioning
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void incrementVersion_bumpsVersionAndResetsLifecycle() {
        IngestedFileEntity doc = entity("ver1");
        doc.setLifecycle(IngestedFileEntity.Lifecycle.TRAINED);
        when(fileRepo.findById("ver1")).thenReturn(Optional.of(doc));
        when(fileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IngestedFileEntity updated = ged.incrementVersion("ver1", "system");

        assertThat(updated.getVersion()).isEqualTo(2);
        assertThat(updated.getLifecycle()).isEqualTo(IngestedFileEntity.Lifecycle.INGESTED);
    }

    @Test
    void incrementVersion_writesReIngestedAudit() {
        IngestedFileEntity doc = entity("ver2");
        when(fileRepo.findById("ver2")).thenReturn(Optional.of(doc));
        when(fileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ged.incrementVersion("ver2", "system");

        verify(auditRepo).save(argThat(a -> a.getAction() == AuditLogEntity.Action.RE_INGESTED));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // R5 — Tags
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void addTags_mergesWithoutDuplicates() {
        IngestedFileEntity doc = entity("tag1");
        doc.setTags(new java.util.ArrayList<>(List.of("kafka")));
        when(fileRepo.findById("tag1")).thenReturn(Optional.of(doc));
        when(fileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IngestedFileEntity updated = ged.addTags("tag1", List.of("kafka", "xml"), "user");

        assertThat(updated.getTags()).containsExactlyInAnyOrder("kafka", "xml");
    }

    @Test
    void addTags_trimsBlanks() {
        IngestedFileEntity doc = entity("tag2");
        when(fileRepo.findById("tag2")).thenReturn(Optional.of(doc));
        when(fileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IngestedFileEntity updated = ged.addTags("tag2", List.of("  pdf  ", " json "), "user");

        assertThat(updated.getTags()).containsExactlyInAnyOrder("pdf", "json");
    }

    @Test
    void addTags_writesAuditEntry() {
        IngestedFileEntity doc = entity("tag3");
        when(fileRepo.findById("tag3")).thenReturn(Optional.of(doc));
        when(fileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ged.addTags("tag3", List.of("test"), "user");

        verify(auditRepo).save(argThat(a -> a.getAction() == AuditLogEntity.Action.TAGGED));
    }

    @Test
    void removeTags_removesOnlySpecifiedTags() {
        IngestedFileEntity doc = entity("tag4");
        doc.setTags(new java.util.ArrayList<>(List.of("kafka", "xml", "pdf")));
        when(fileRepo.findById("tag4")).thenReturn(Optional.of(doc));
        when(fileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IngestedFileEntity updated = ged.removeTags("tag4", List.of("xml"), "user");

        assertThat(updated.getTags()).containsExactlyInAnyOrder("kafka", "pdf");
    }

    @Test
    void removeTags_writesUntaggedAudit() {
        IngestedFileEntity doc = entity("tag5");
        doc.setTags(new java.util.ArrayList<>(List.of("kafka")));
        when(fileRepo.findById("tag5")).thenReturn(Optional.of(doc));
        when(fileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ged.removeTags("tag5", List.of("kafka"), "user");

        verify(auditRepo).save(argThat(a -> a.getAction() == AuditLogEntity.Action.UNTAGGED));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // R1 — Liens document ↔ modèle
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void linkToModel_createsNewLink() {
        when(linkRepo.existsByDocumentSha256AndModelNameAndLinkType(
                "sha1", "phi-mini", DocumentModelLinkEntity.LinkType.TRAINED_ON)).thenReturn(false);
        when(linkRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DocumentModelLinkEntity link = ged.linkToModel(
                "sha1", "phi-mini", DocumentModelLinkEntity.LinkType.TRAINED_ON, "system");

        assertThat(link.getModelName()).isEqualTo("phi-mini");
        assertThat(link.getLinkType()).isEqualTo(DocumentModelLinkEntity.LinkType.TRAINED_ON);
        verify(linkRepo).save(any());
    }

    @Test
    void linkToModel_idempotent_doesNotCreateDuplicate() {
        DocumentModelLinkEntity existing = new DocumentModelLinkEntity(
                "sha1", "phi-mini", DocumentModelLinkEntity.LinkType.TRAINED_ON, Instant.now());
        when(linkRepo.existsByDocumentSha256AndModelNameAndLinkType(
                "sha1", "phi-mini", DocumentModelLinkEntity.LinkType.TRAINED_ON)).thenReturn(true);
        when(linkRepo.findByDocumentSha256("sha1")).thenReturn(List.of(existing));

        ged.linkToModel("sha1", "phi-mini", DocumentModelLinkEntity.LinkType.TRAINED_ON, "system");

        verify(linkRepo, never()).save(any());
    }

    @Test
    void linkToModel_writesAuditEntry() {
        when(linkRepo.existsByDocumentSha256AndModelNameAndLinkType(any(), any(), any()))
                .thenReturn(false);
        when(linkRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ged.linkToModel("sha1", "phi-mini", DocumentModelLinkEntity.LinkType.EVALUATED_ON, "system");

        verify(auditRepo).save(argThat(a -> a.getAction() == AuditLogEntity.Action.LINKED_TO_MODEL));
    }

    @Test
    void getDocumentsByModel_returnsOnlyMatchingDocs() {
        DocumentModelLinkEntity link = new DocumentModelLinkEntity(
                "sha-match", "phi-mini", DocumentModelLinkEntity.LinkType.TRAINED_ON, Instant.now());
        when(linkRepo.findByModelName("phi-mini")).thenReturn(List.of(link));
        when(fileRepo.findById("sha-match")).thenReturn(Optional.of(entity("sha-match")));

        List<IngestedFileEntity> docs = ged.getDocumentsByModel("phi-mini");

        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).getSha256()).isEqualTo("sha-match");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // R6 — Audit trail
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void audit_savesEntryWithCorrectFields() {
        ged.audit("sha1", AuditLogEntity.Action.INGESTED, "system",
                Map.of("chunks", "5", "quality", "0.75"));

        verify(auditRepo).save(argThat(a ->
                a.getDocumentSha256().equals("sha1") &&
                a.getAction() == AuditLogEntity.Action.INGESTED &&
                "system".equals(a.getActor()) &&
                a.getDetails() != null && a.getDetails().contains("chunks")));
    }

    @Test
    void audit_nullActor_defaultsToSystem() {
        ged.audit("sha1", AuditLogEntity.Action.TAGGED, null, null);

        verify(auditRepo).save(argThat(a -> "system".equals(a.getActor())));
    }

    @Test
    void getAuditTrail_delegatesToRepository() {
        AuditLogEntity entry = new AuditLogEntity(
                "sha1", AuditLogEntity.Action.INGESTED, "system", Instant.now(), null);
        when(auditRepo.findByDocumentSha256OrderByTimestampDesc("sha1"))
                .thenReturn(List.of(entry));

        List<AuditLogEntity> trail = ged.getAuditTrail("sha1");

        assertThat(trail).hasSize(1);
        assertThat(trail.get(0).getAction()).isEqualTo(AuditLogEntity.Action.INGESTED);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // R7 — Score de qualité
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void computeQualityScore_zeroChunks_returnsZero() {
        assertThat(GedService.computeQualityScore(0, "TXT")).isEqualTo(0.0);
    }

    @Test
    void computeQualityScore_twentyChunks_nearMax() {
        double score = GedService.computeQualityScore(20, "TXT");
        assertThat(score).isGreaterThan(0.75);
    }

    @Test
    void computeQualityScore_pdfBonus_higherThanTxt() {
        double txt = GedService.computeQualityScore(10, "TXT");
        double pdf = GedService.computeQualityScore(10, "PDF");
        assertThat(pdf).isGreaterThan(txt);
    }

    @Test
    void computeQualityScore_docxBonus_higherThanTxt() {
        double txt  = GedService.computeQualityScore(10, "TXT");
        double docx = GedService.computeQualityScore(10, "DOCX");
        assertThat(docx).isGreaterThan(txt);
    }

    @Test
    void computeQualityScore_neverExceedsOne() {
        assertThat(GedService.computeQualityScore(9999, "PDF")).isLessThanOrEqualTo(1.0);
    }

    @Test
    void computeQualityScore_nullFormat_noException() {
        assertThat(GedService.computeQualityScore(5, null)).isGreaterThanOrEqualTo(0.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Queries
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // Amélioration 1 — Machine à états
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void transitionLifecycle_forbiddenTransition_throwsIllegalState() {
        IngestedFileEntity doc = entity("fsm1");
        // INGESTED → TRAINED est interdit
        when(fileRepo.findById("fsm1")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() ->
                ged.transitionLifecycle("fsm1", IngestedFileEntity.Lifecycle.TRAINED, "user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Transition interdite");
    }

    @Test
    void transitionLifecycle_allowedChain_succeeds() {
        // INGESTED → QUALIFIED → TRAINED → ARCHIVED
        IngestedFileEntity doc = entity("fsm2");
        when(fileRepo.findById("fsm2")).thenReturn(Optional.of(doc));
        when(fileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(linkRepo.findByDocumentSha256(anyString())).thenReturn(List.of());

        ged.transitionLifecycle("fsm2", IngestedFileEntity.Lifecycle.QUALIFIED, "u");
        ged.transitionLifecycle("fsm2", IngestedFileEntity.Lifecycle.TRAINED, "u");
        ged.transitionLifecycle("fsm2", IngestedFileEntity.Lifecycle.ARCHIVED, "u");

        assertThat(doc.getLifecycle()).isEqualTo(IngestedFileEntity.Lifecycle.ARCHIVED);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Amélioration 3 — Suppression synchronisée
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void deleteDocument_removesFromRepoAndReturnsResult() {
        IngestedFileEntity doc = entity("del1");
        doc.setCollectionName("spectra_documents");
        when(fileRepo.findById("del1")).thenReturn(Optional.of(doc));
        when(linkRepo.findByDocumentSha256("del1")).thenReturn(List.of());
        when(auditRepo.findByDocumentSha256OrderByTimestampDesc("del1")).thenReturn(List.of());

        Map<String, Object> result = ged.deleteDocument("del1", "api");

        verify(fileRepo).delete(doc);
        assertThat(result).containsKey("sha256");
        assertThat(result.get("sha256")).isEqualTo("del1");
    }

    @Test
    void deleteDocument_unknownDoc_throwsNoSuchElement() {
        when(fileRepo.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ged.deleteDocument("missing", "api"))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Amélioration 5 — Statistiques
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void stats_returnsExpectedKeys() {
        when(fileRepo.countByLifecycle()).thenReturn(List.of(
                new Object[]{IngestedFileEntity.Lifecycle.INGESTED, 5L},
                new Object[]{IngestedFileEntity.Lifecycle.QUALIFIED, 2L}
        ));
        when(fileRepo.avgQualityScore()).thenReturn(0.72);
        when(fileRepo.sumChunks()).thenReturn(100L);
        when(fileRepo.findAllByOrderByIngestedAtDesc()).thenReturn(List.of(entity("s1")));

        Map<String, Object> stats = ged.stats();

        assertThat(stats).containsKeys("byLifecycle", "total", "avgQualityScore",
                "qualityDistribution", "topTags", "totalChunks");
    }

    @Test
    void stats_total_sumsAllLifecycles() {
        when(fileRepo.countByLifecycle()).thenReturn(List.of(
                new Object[]{IngestedFileEntity.Lifecycle.INGESTED, 3L},
                new Object[]{IngestedFileEntity.Lifecycle.TRAINED, 7L}
        ));
        when(fileRepo.findAllByOrderByIngestedAtDesc()).thenReturn(List.of());

        Map<String, Object> stats = ged.stats();

        assertThat((Long) stats.get("total")).isEqualTo(10L);
    }

    @Test
    void stats_topTags_countedCorrectly() {
        IngestedFileEntity d1 = entity("t1"); d1.setTags(List.of("kafka", "xml"));
        IngestedFileEntity d2 = entity("t2"); d2.setTags(List.of("kafka"));
        when(fileRepo.findAllByOrderByIngestedAtDesc()).thenReturn(List.of(d1, d2));
        when(fileRepo.countByLifecycle()).thenReturn(List.of());

        Map<String, Object> stats = ged.stats();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topTags = (List<Map<String, Object>>) stats.get("topTags");
        assertThat(topTags.get(0).get("tag")).isEqualTo("kafka");
        assertThat(topTags.get(0).get("count")).isEqualTo(2L);
    }

    @Test
    void findAll_delegatesToRepository() {
        when(fileRepo.findAllByOrderByIngestedAtDesc()).thenReturn(List.of(entity("a"), entity("b")));
        assertThat(ged.findAll()).hasSize(2);
    }

    @Test
    void findByLifecycle_delegatesToRepository() {
        when(fileRepo.findByLifecycleOrderByIngestedAtDesc(IngestedFileEntity.Lifecycle.QUALIFIED))
                .thenReturn(List.of(entity("q1")));

        List<IngestedFileEntity> result = ged.findByLifecycle(IngestedFileEntity.Lifecycle.QUALIFIED);

        assertThat(result).hasSize(1);
    }

    @Test
    void findById_returnsEmpty_whenNotFound() {
        when(fileRepo.findById("missing")).thenReturn(Optional.empty());
        assertThat(ged.findById("missing")).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static IngestedFileEntity entity(String sha256) {
        return new IngestedFileEntity(sha256, "doc.pdf", "PDF",
                Instant.now(), 5, "spectra_documents", 0.6);
    }
}
