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
     * Le moteur est-il en état de classer ? Contrôle <b>local et bon marché</b>, appelé sur le
     * chemin de requête — contrairement à {@link #checkHealth()}, qui peut faire un aller-retour
     * réseau et n'a donc sa place que sur {@code /api/status}.
     *
     * <p><b>Pourquoi cette méthode existe.</b> Le reranking est désormais actif par défaut, et
     * le moteur ONNX crée son bean même quand l'artefact est absent — délibérément, pour que
     * {@code /api/status} publie la cause au lieu de taire la panne. Mais {@code RagService}
     * décidait d'utiliser le reranker sur la seule <i>présence</i> du bean : un moteur non
     * chargé faisait alors, à <b>chaque</b> requête, sur-extraire {@code topCandidates} chunks
     * (20 au lieu de 5) auprès de ChromaDB, échouer, tronquer, et journaliser un avertissement.
     * Fonctionnellement correct, mais coûteux et bruyant — et permanent, puisque rien ne le
     * corrigeait au fil de l'eau.
     *
     * <p>Distinguer « présent » de « utilisable » supprime ce coût : une installation sans
     * artefact se comporte exactement comme si le reranking était éteint, tout en restant
     * diagnosticable sur {@code /api/status}.
     *
     * <p>Défaut {@code true} : une implémentation distante ne peut pas répondre à cette question
     * sans appel réseau, et doit donc être tentée puis échouer — comportement inchangé.
     */
    default boolean isAvailable() {
        return true;
    }

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
