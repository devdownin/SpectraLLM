package fr.spectra.dto;

import java.util.Map;

/**
 * @param byCategory        répartition par nature de paire (qa, summary, negative…)
 * @param byDocumentCategory répartition par catégorie du <b>document source</b> (R8) — c'est
 *                          cette vue qui révèle un thème sur- ou sous-représenté dans le
 *                          corpus d'entraînement. La clé {@code (non classé)} regroupe les
 *                          paires issues de documents sans classification.
 */
public record DatasetStats(
        int totalPairs,
        int chunksInStore,
        Map<String, Integer> byCategory,
        Map<String, Integer> byType,
        Map<String, Integer> byDocumentCategory,
        double avgConfidence
) {
}
