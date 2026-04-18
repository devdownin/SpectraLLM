package fr.spectra.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Résultat d'un benchmark de performance pour un composant Spectra.
 *
 * <p>Les métriques de latence sont en millisecondes.
 * Le débit est exprimé en unités/seconde selon le type de benchmark :
 * tokens/s pour le chat LLM, vecteurs/s pour les embeddings.
 */
public record BenchmarkResult(
        /** Identifiant du benchmark (ex. "rag_latency", "embedding_throughput"). */
        String name,
        /** Modèle actif au moment du benchmark. */
        String model,
        /** Nombre d'itérations exécutées. */
        int iterations,
        /** Latence minimale observée (ms). */
        long minMs,
        /** Latence maximale observée (ms). */
        long maxMs,
        /** Latence médiane P50 (ms). */
        long p50Ms,
        /** Latence 95e percentile P95 (ms). */
        long p95Ms,
        /** Latence moyenne (ms). */
        double avgMs,
        /** Débit estimé (tokens/s ou vecteurs/s selon le benchmark). */
        double throughput,
        /** Unité du débit (ex. "tokens/s", "vectors/s"). */
        String throughputUnit,
        /** Horodatage de début du benchmark. */
        Instant startedAt,
        /** Durée totale du benchmark (toutes itérations incluses), en ms. */
        long totalDurationMs,
        /** Métadonnées supplémentaires spécifiques au benchmark. */
        Map<String, Object> details
) {
    /**
     * Calcule le percentile P d'une liste de latences triées.
     * Utilise l'interpolation linéaire (méthode "nearest rank").
     */
    public static long percentile(List<Long> sortedMs, double p) {
        if (sortedMs.isEmpty()) return 0;
        int index = (int) Math.ceil(p / 100.0 * sortedMs.size()) - 1;
        return sortedMs.get(Math.max(0, Math.min(index, sortedMs.size() - 1)));
    }
}
