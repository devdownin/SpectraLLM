package fr.spectra.service;

import fr.spectra.dto.InstallationJob;
import fr.spectra.persistence.InstallationJobEntity;
import fr.spectra.persistence.InstallationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Politique de rétention de l'historique des installations Model Hub.
 *
 * <p>Chaque téléchargement laisse un job persisté (H2) ; sans purge, l'historique
 * enfle sans limite et noie les téléchargements récents sous des années d'entrées.
 * Ce cron nocturne supprime les jobs <b>terminaux</b> (COMPLETED/FAILED/CANCELLED)
 * plus vieux que {@code llmfit.installations-retention-days} jours. Les jobs
 * non-terminaux ne sont jamais purgés : soit un téléchargement est réellement en
 * cours, soit la réconciliation au démarrage les passera FAILED — ils redeviendront
 * alors purgeables au cycle suivant.</p>
 *
 * <p>Désactivé par défaut ({@code 0}) — même convention que les rétentions GED et
 * Kafka. Miroir de {@link KafkaStreamRetentionService}.</p>
 */
@Service
public class InstallationRetentionService {

    private static final Logger log = LoggerFactory.getLogger(InstallationRetentionService.class);

    private final InstallationJobRepository repository;
    private final int retentionDays;

    public InstallationRetentionService(
            InstallationJobRepository repository,
            @Value("${llmfit.installations-retention-days:0}") int retentionDays) {
        this.repository = repository;
        this.retentionDays = retentionDays;
    }

    /** Purge planifiée (par défaut 03h40). Le TTL est vérifié à l'exécution (0 = désactivé). */
    @Scheduled(cron = "${llmfit.installations-retention-cron:0 40 3 * * *}")
    public void purgeOldInstallations() {
        if (retentionDays <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        List<InstallationJobEntity> stale = repository.findAll().stream()
                .filter(entity -> {
                    InstallationJob job = entity.toDto();
                    boolean terminal = job.status() == InstallationJob.Status.COMPLETED
                            || job.status() == InstallationJob.Status.FAILED
                            || job.status() == InstallationJob.Status.CANCELLED;
                    // Repère temporel : fin du job, ou création s'il n'en a pas
                    // (donnée héritée) — jamais purgé si aucune date n'est connue.
                    Instant reference = job.completedAt() != null ? job.completedAt() : job.createdAt();
                    return terminal && reference != null && reference.isBefore(cutoff);
                })
                .toList();
        if (stale.isEmpty()) {
            return;
        }
        try {
            repository.deleteAll(stale);
            log.info("Rétention Model Hub : {} installation(s) terminée(s) purgée(s) de "
                    + "l'historique (TTL={} j)", stale.size(), retentionDays);
        } catch (Exception e) {
            log.warn("Rétention Model Hub : purge de l'historique impossible : {}", e.getMessage());
        }
    }
}
