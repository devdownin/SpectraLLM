package fr.spectra.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calcule automatiquement les limites mémoire d'ingestion en fonction de la
 * configuration du serveur (heap JVM, concurrence).
 *
 * <p>Le pipeline lit chaque fichier en mémoire (octets → String UTF‑16 → passes
 * de nettoyage → chunks), avec un facteur d'amplification d'environ 6×. Le
 * plafond de taille décompressée par fichier/entrée ZIP est donc dérivé du heap
 * disponible, réparti sur les ingestions concurrentes, pour éviter
 * l'OutOfMemoryError.</p>
 */
public final class IngestionLimits {

    private static final Logger log = LoggerFactory.getLogger(IngestionLimits.class);

    /** Fraction du heap réservée aux tampons d'ingestion (le reste : app, caches, etc.). */
    private static final double HEAP_FRACTION = 0.4;
    /** Amplification mémoire lecture → String UTF-16 → nettoyage → chunks. */
    private static final int AMPLIFICATION = 6;
    private static final long MIN_MB = 8;
    private static final long MAX_MB = 512;

    private IngestionLimits() {}

    /**
     * Résout la taille décompressée maximale autorisée par fichier/entrée (octets).
     *
     * @param configuredMb       valeur explicite en Mo ; si &le; 0 → calcul automatique
     * @param concurrentIngestions nombre d'ingestions simultanées (partage du heap)
     */
    public static long resolveMaxUncompressedBytes(int configuredMb, int concurrentIngestions) {
        if (configuredMb > 0) {
            log.info("[ingestion] Limite mémoire configurée explicitement : {} Mo", configuredMb);
            return configuredMb * 1024L * 1024L; // override explicite
        }
        long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        int conc = Math.max(1, concurrentIngestions);
        long autoMb = (long) (maxHeapMb * HEAP_FRACTION / conc / AMPLIFICATION);
        autoMb = Math.max(MIN_MB, Math.min(autoMb, MAX_MB));
        log.info("[ingestion] Limite mémoire auto-calculée : {} Mo par fichier (heap={} Mo, concurrence={})",
                autoMb, maxHeapMb, conc);
        return autoMb * 1024L * 1024L;
    }
}
