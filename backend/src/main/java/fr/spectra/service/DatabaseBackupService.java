package fr.spectra.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * Sauvegarde périodique de la base H2 (R8 — fiabilité des données).
 *
 * <p>La base est un fichier unique : sa perte ou sa corruption efface toutes les
 * métadonnées GED / jobs. Ce service exécute la commande H2 {@code BACKUP TO}
 * (cohérente, à chaud) vers un répertoire dédié, et applique une rétention.</p>
 *
 * <p>Désactivable via {@code spectra.backup.enabled=false}. Inactif si la base
 * n'est pas H2.</p>
 */
@Service
public class DatabaseBackupService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBackupService.class);
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;
    private final boolean isH2;
    private final Path backupDir;
    private final int retention;

    public DatabaseBackupService(JdbcTemplate jdbcTemplate,
                                 @Value("${spring.datasource.url:}") String datasourceUrl,
                                 @Value("${spectra.backup.enabled:true}") boolean enabled,
                                 @Value("${spectra.backup.dir:./data/backups}") String dir,
                                 @Value("${spectra.backup.retention:7}") int retention) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
        this.isH2 = datasourceUrl != null && datasourceUrl.startsWith("jdbc:h2:");
        this.backupDir = Path.of(dir);
        this.retention = Math.max(1, retention);
        if (enabled && !isH2) {
            log.info("[backup] base non-H2 — sauvegarde automatique désactivée");
        }
    }

    /** Tourne chaque nuit à 03:00. */
    @Scheduled(cron = "${spectra.backup.cron:0 0 3 * * *}")
    public void backup() {
        if (!enabled || !isH2) return;
        try {
            Files.createDirectories(backupDir);
            Path target = backupDir.resolve("spectra-db-" + STAMP.format(Instant.now()) + ".zip");
            // Chemin construit côté serveur (pas d'entrée utilisateur) → injection SQL non applicable.
            jdbcTemplate.execute("BACKUP TO '" + target.toAbsolutePath() + "'");
            log.info("[backup] base sauvegardée → {}", target);
            pruneOldBackups();
        } catch (Exception e) {
            log.error("[backup] échec de la sauvegarde : {}", e.getMessage(), e);
        }
    }

    /** Conserve les {@code retention} sauvegardes les plus récentes. */
    private void pruneOldBackups() throws IOException {
        try (var stream = Files.list(backupDir)) {
            List<Path> backups = stream
                    .filter(p -> p.getFileName().toString().startsWith("spectra-db-")
                            && p.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .toList();
            for (int i = retention; i < backups.size(); i++) {
                try {
                    Files.deleteIfExists(backups.get(i));
                    log.debug("[backup] ancienne sauvegarde supprimée : {}", backups.get(i));
                } catch (Exception e) {
                    log.warn("[backup] impossible de supprimer {} : {}", backups.get(i), e.getMessage());
                }
            }
        }
    }
}
