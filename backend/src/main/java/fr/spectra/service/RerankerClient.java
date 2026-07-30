package fr.spectra.service;

import fr.spectra.dto.ServiceStatus;

import java.util.List;

/**
 * Cross-Encoder re-ranking client.
 * Scores (query, document) pairs and returns indices sorted by relevance.
 */
public interface RerankerClient {

    /**
     * État de santé du moteur de reranking, publié par {@code /api/status} et
     * {@code /api/health}.
     *
     * <p>Déclaré ici — et non sur la seule implémentation HTTP — pour que les contrôleurs
     * dépendent de l'abstraction : ils injectaient {@code CrossEncoderRerankerClient}, si bien
     * qu'une implémentation alternative (moteur exécuté dans la JVM, cf.
     * {@code docs/process/audit-python-java.fr.md}) aurait disparu des deux endpoints sans
     * qu'aucun test n'échoue.
     *
     * <p>Une implémentation locale n'ayant pas d'URL renseigne {@code url} avec ce qui
     * identifie le moteur (nom du modèle, chemin de l'artefact).
     */
    ServiceStatus checkHealth();

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
