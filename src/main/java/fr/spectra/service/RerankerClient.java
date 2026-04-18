package fr.spectra.service;

import java.util.List;

/**
 * Cross-Encoder re-ranking client.
 * Scores (query, document) pairs and returns indices sorted by relevance.
 */
public interface RerankerClient {

    /**
     * Re-ranks {@code documents} against {@code query} and returns the top-N results
     * sorted by descending score.
     *
     * @param query     the user question
     * @param documents candidate document texts
     * @param topN      number of results to return (≤ documents.size())
     * @return ordered list of ranked results
     */
    List<RankedResult> rerank(String query, List<String> documents, int topN);

    record RankedResult(int index, float score) {}
}
