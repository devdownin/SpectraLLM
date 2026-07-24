package fr.spectra.service;

import fr.spectra.dto.InstallationJob;
import fr.spectra.persistence.InstallationJobEntity;
import fr.spectra.persistence.InstallationJobRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rétention de l'historique des installations Model Hub : seuls les jobs TERMINAUX
 * plus vieux que le TTL sont purgés — jamais un téléchargement en cours, jamais un
 * job récent, et rien du tout quand la rétention est désactivée (0, défaut).
 */
class InstallationRetentionServiceTest {

    private static InstallationJobEntity job(String id, InstallationJob.Status status, Instant completedAt) {
        return InstallationJobEntity.fromDto(new InstallationJob(
                id, status, "modele-" + id, null, false, 100, "step", null, null, null,
                completedAt != null ? completedAt.minusSeconds(60) : Instant.now(), completedAt));
    }

    @Test
    void purge_supprimeSeulementLesJobsTerminauxPlusVieuxQueLeTtl() {
        InstallationJobRepository repo = mock(InstallationJobRepository.class);
        Instant old = Instant.now().minus(40, ChronoUnit.DAYS);
        InstallationJobEntity vieuxCompleted = job("old-ok", InstallationJob.Status.COMPLETED, old);
        InstallationJobEntity vieuxFailed = job("old-ko", InstallationJob.Status.FAILED, old);
        InstallationJobEntity recentCompleted = job("recent", InstallationJob.Status.COMPLETED, Instant.now());
        // Non-terminal : jamais purgé, même ancien — la réconciliation au démarrage
        // le passera FAILED et il sera purgeable au cycle suivant.
        InstallationJobEntity vieuxEnCours = InstallationJobEntity.fromDto(new InstallationJob(
                "stuck", InstallationJob.Status.DOWNLOADING, "modele-stuck", null, false, 42, "step",
                null, null, null, old, null));
        when(repo.findAll()).thenReturn(List.of(vieuxCompleted, vieuxFailed, recentCompleted, vieuxEnCours));

        new InstallationRetentionService(repo, 30).purgeOldInstallations();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<InstallationJobEntity>> captor =
                ArgumentCaptor.forClass((Class) Iterable.class);
        verify(repo).deleteAll(captor.capture());
        List<String> purged = java.util.stream.StreamSupport.stream(captor.getValue().spliterator(), false)
                .map(e -> e.toDto().jobId())
                .toList();
        assertThat(purged).containsExactlyInAnyOrder("old-ok", "old-ko");
    }

    @Test
    void purge_ttlZero_neFaitRien() {
        InstallationJobRepository repo = mock(InstallationJobRepository.class);

        new InstallationRetentionService(repo, 0).purgeOldInstallations();

        verify(repo, never()).findAll();
        verify(repo, never()).deleteAll(anyIterable());
    }

    @Test
    void purge_aucunJobPerime_neSupprimeRien() {
        InstallationJobRepository repo = mock(InstallationJobRepository.class);
        when(repo.findAll()).thenReturn(List.of(
                job("recent", InstallationJob.Status.COMPLETED, Instant.now())));

        new InstallationRetentionService(repo, 30).purgeOldInstallations();

        verify(repo, never()).deleteAll(anyIterable());
    }
}
