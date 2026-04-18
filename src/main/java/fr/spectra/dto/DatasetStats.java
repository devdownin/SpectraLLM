package fr.spectra.dto;

import java.util.Map;

public record DatasetStats(
        int totalPairs,
        int chunksInStore,
        Map<String, Integer> byCategory,
        Map<String, Integer> byType,
        double avgConfidence
) {
}
