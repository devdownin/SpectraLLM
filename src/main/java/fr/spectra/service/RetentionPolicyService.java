package fr.spectra.service;

import fr.spectra.persistence.IngestedFileEntity;
import fr.spectra.persistence.IngestedFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * R8 — Politique de rétention automatique des artefacts.
 * Passe automatiquement en ARCHIVED les documents INGESTED vieux de N jours
 * et supprime les entrées ARCHIVED vieilles de M jours.
 */
@Service
public class RetentionPolicyService {

    private static final Logger log = LoggerFactory.getLogger(RetentionPolicyService.class);

    private final IngestedFileRepository fileRepo;
    private final GedService gedService;

    /** Jours après lesquels un document INGESTED est auto-archivé. 0 = désactivé. */
    private final int archiveAfterDays;

    /** Jours après lesquels un document ARCHIVED est purgé de la DB. 0 = désactivé. */
    private final int purgeAfterDays;

    public RetentionPolicyService(
            IngestedFileRepository fileRepo,
            GedService gedService,
            fr.spectra.config.SpectraProperties properties) {
        this.fileRepo = fileRepo;
        this.gedService = gedService;
        fr.spectra.config.SpectraProperties.GedProperties ged = properties.ged();
        this.archiveAfterDays = ged != null && ged.retention() != null
                ? ged.retention().effectiveArchiveAfterDays() : 0;
        this.purgeAfterDays = ged != null && ged.retention() != null
                ? ged.retention().effectivePurgeAfterDays() : 0;
    }

    /** Tourne chaque nuit à minuit. */
    @Scheduled(cron = "0 0 0 * * *")
    public void applyRetentionPolicy() {
        if (archiveAfterDays > 0) autoArchive();
        if (purgeAfterDays > 0)   autoPurge();
    }

    private void autoArchive() {
        Instant cutoff = Instant.now().minus(archiveAfterDays, ChronoUnit.DAYS);
        List<IngestedFileEntity> candidates =
                fileRepo.findByLifecycleOrderByIngestedAtDesc(IngestedFileEntity.Lifecycle.INGESTED)
                        .stream()
                        .filter(d -> d.getIngestedAt().isBefore(cutoff))
                        .toList();
        for (IngestedFileEntity doc : candidates) {
            gedService.transitionLifecycle(doc.getSha256(),
                    IngestedFileEntity.Lifecycle.ARCHIVED, "retention-policy");
            log.info("Rétention : document {} archivé automatiquement", doc.getSha256());
        }
        if (!candidates.isEmpty()) log.info("Rétention : {} document(s) archivé(s)", candidates.size());
    }

    private void autoPurge() {
        Instant cutoff = Instant.now().minus(purgeAfterDays, ChronoUnit.DAYS);
        List<IngestedFileEntity> toDelete =
                fileRepo.findByLifecycleOrderByIngestedAtDesc(IngestedFileEntity.Lifecycle.ARCHIVED)
                        .stream()
                        .filter(d -> d.getIngestedAt().isBefore(cutoff))
                        .toList();
        for (IngestedFileEntity doc : toDelete) {
            fileRepo.delete(doc);
            log.info("Rétention : document {} purgé de la DB", doc.getSha256());
        }
        if (!toDelete.isEmpty()) log.info("Rétention : {} document(s) purgé(s)", toDelete.size());
    }
}
