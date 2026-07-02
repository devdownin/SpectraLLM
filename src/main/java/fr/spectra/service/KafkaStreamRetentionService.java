package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.persistence.StreamSourceEntity;
import fr.spectra.persistence.StreamSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Politique de rétention du flux Kafka — purge les données vivantes périmées.
 *
 * <p>Un flux continu fait croître l'index sans limite, ce qui dégrade la latence et la
 * pertinence de la recherche. Ce cron nocturne supprime les sources qui n'ont pas été mises à
 * jour depuis {@code spectra.kafka.retention-ttl-days} jours (des deux index + de la table de
 * suivi). Désactivé si {@code retention-ttl-days <= 0}.</p>
 *
 * <p>Actif uniquement quand {@code spectra.kafka.enabled=true}.</p>
 */
@Service
@ConditionalOnProperty(prefix = "spectra.kafka", name = "enabled", havingValue = "true")
public class KafkaStreamRetentionService {

    private static final Logger log = LoggerFactory.getLogger(KafkaStreamRetentionService.class);

    private final StreamSourceRepository repository;
    private final IngestionService ingestionService;
    private final int ttlDays;

    public KafkaStreamRetentionService(StreamSourceRepository repository,
                                       IngestionService ingestionService,
                                       SpectraProperties properties) {
        this.repository = repository;
        this.ingestionService = ingestionService;
        this.ttlDays = properties.kafka() != null ? properties.kafka().effectiveRetentionTtlDays() : 0;
    }

    /** Purge planifiée (par défaut 03h30). Le TTL est vérifié à l'exécution (0 = désactivé). */
    @Scheduled(cron = "${spectra.kafka.retention-cron:0 30 3 * * *}")
    public void purgeStale() {
        if (ttlDays <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(ttlDays, ChronoUnit.DAYS);
        List<StreamSourceEntity> stale = repository.findByLastUpdatedAtBefore(cutoff);
        if (stale.isEmpty()) {
            return;
        }
        int purged = 0;
        for (StreamSourceEntity s : stale) {
            try {
                ingestionService.purgeStreamSource(s.getSourceKey(), s.getCollection());
                purged++;
            } catch (Exception e) {
                log.warn("Rétention Kafka : purge de '{}' échouée : {}", s.getSourceKey(), e.getMessage());
            }
        }
        log.info("Rétention Kafka : {} source(s) périmée(s) purgée(s) (TTL={} j)", purged, ttlDays);
    }
}
