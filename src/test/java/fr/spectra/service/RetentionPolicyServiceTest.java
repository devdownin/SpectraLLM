package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.persistence.IngestedFileEntity;
import fr.spectra.persistence.IngestedFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires de RetentionPolicyService (R8).
 */
class RetentionPolicyServiceTest {

    @TempDir
    Path tempArchive;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private IngestedFileRepository fileRepo(List<IngestedFileEntity> ingested,
                                             List<IngestedFileEntity> archived) {
        IngestedFileRepository repo = mock(IngestedFileRepository.class);
        when(repo.findByLifecycleOrderByIngestedAtDesc(IngestedFileEntity.Lifecycle.INGESTED))
                .thenReturn(ingested);
        when(repo.findByLifecycleOrderByIngestedAtDesc(IngestedFileEntity.Lifecycle.ARCHIVED))
                .thenReturn(archived);
        return repo;
    }

    private GedService gedService(IngestedFileRepository repo) {
        fr.spectra.persistence.DocumentModelLinkRepository linkRepo =
                mock(fr.spectra.persistence.DocumentModelLinkRepository.class);
        fr.spectra.persistence.AuditLogRepository auditRepo =
                mock(fr.spectra.persistence.AuditLogRepository.class);
        when(repo.findAllByOrderByIngestedAtDesc()).thenReturn(List.of());
        when(repo.countByLifecycle()).thenReturn(List.of());
        return spy(new GedService(repo, linkRepo, auditRepo,
                mock(ChromaDbClient.class), mock(FtsService.class),
                tempArchive.toString()));
    }

    private static SpectraProperties props(int archiveDays, int purgeDays) {
        SpectraProperties.GedProperties.RetentionProperties ret =
                new SpectraProperties.GedProperties.RetentionProperties(archiveDays, purgeDays);
        SpectraProperties.GedProperties ged =
                new SpectraProperties.GedProperties("./data/archive", 0.0, ret);
        SpectraProperties p = mock(SpectraProperties.class);
        when(p.ged()).thenReturn(ged);
        return p;
    }

    // ── archiveAfterDays > 0 ──────────────────────────────────────────────────

    @Test
    void applyRetentionPolicy_archivesOldIngestedDocuments() {
        IngestedFileEntity old = entity("old1", Instant.now().minus(31, ChronoUnit.DAYS),
                IngestedFileEntity.Lifecycle.INGESTED);
        IngestedFileRepository repo = fileRepo(List.of(old), List.of());
        GedService ged = gedService(repo);
        when(repo.findById("old1")).thenReturn(java.util.Optional.of(old));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mock(fr.spectra.persistence.DocumentModelLinkRepository.class)
                .findByDocumentSha256(any())).thenReturn(List.of());

        RetentionPolicyService svc = new RetentionPolicyService(repo, ged, props(30, 0));
        svc.applyRetentionPolicy();

        verify(repo, atLeastOnce()).save(argThat(d ->
                d.getLifecycle() == IngestedFileEntity.Lifecycle.ARCHIVED));
    }

    @Test
    void applyRetentionPolicy_doesNotArchiveRecentDocuments() {
        IngestedFileEntity recent = entity("recent1", Instant.now().minus(5, ChronoUnit.DAYS),
                IngestedFileEntity.Lifecycle.INGESTED);
        IngestedFileRepository repo = fileRepo(List.of(recent), List.of());
        GedService ged = gedService(repo);

        RetentionPolicyService svc = new RetentionPolicyService(repo, ged, props(30, 0));
        svc.applyRetentionPolicy();

        verify(repo, never()).save(any());
    }

    // ── purgeAfterDays > 0 ────────────────────────────────────────────────────

    @Test
    void applyRetentionPolicy_purgesOldArchivedDocuments() {
        IngestedFileEntity old = entity("arch1", Instant.now().minus(100, ChronoUnit.DAYS),
                IngestedFileEntity.Lifecycle.ARCHIVED);
        IngestedFileRepository repo = fileRepo(List.of(), List.of(old));
        GedService ged = gedService(repo);

        RetentionPolicyService svc = new RetentionPolicyService(repo, ged, props(0, 90));
        svc.applyRetentionPolicy();

        verify(repo).delete(old);
    }

    @Test
    void applyRetentionPolicy_doesNotPurgeRecentArchivedDocuments() {
        IngestedFileEntity recent = entity("arch2", Instant.now().minus(10, ChronoUnit.DAYS),
                IngestedFileEntity.Lifecycle.ARCHIVED);
        IngestedFileRepository repo = fileRepo(List.of(), List.of(recent));
        GedService ged = gedService(repo);

        RetentionPolicyService svc = new RetentionPolicyService(repo, ged, props(0, 90));
        svc.applyRetentionPolicy();

        verify(repo, never()).delete(any(IngestedFileEntity.class));
    }

    // ── disabled (0) ──────────────────────────────────────────────────────────

    @Test
    void applyRetentionPolicy_disabled_nothingHappens() {
        IngestedFileEntity old = entity("any", Instant.now().minus(999, ChronoUnit.DAYS),
                IngestedFileEntity.Lifecycle.INGESTED);
        IngestedFileRepository repo = fileRepo(List.of(old), List.of(old));
        GedService ged = gedService(repo);

        RetentionPolicyService svc = new RetentionPolicyService(repo, ged, props(0, 0));
        svc.applyRetentionPolicy();

        verify(repo, never()).save(any());
        verify(repo, never()).delete(any(IngestedFileEntity.class));
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static IngestedFileEntity entity(String sha256, Instant ingestedAt,
                                              IngestedFileEntity.Lifecycle lifecycle) {
        IngestedFileEntity e = new IngestedFileEntity(
                sha256, "doc.pdf", "PDF", ingestedAt, 5, "spectra_documents", 0.5);
        e.setLifecycle(lifecycle);
        return e;
    }
}
