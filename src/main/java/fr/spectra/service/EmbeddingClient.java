package fr.spectra.service;

import fr.spectra.dto.ServiceStatus;

import java.util.List;
import java.util.Map;

/**
 * Abstraction du provider d'embeddings.
 */
public interface EmbeddingClient {

    List<Float> embed(String text);

    List<List<Float>> embedBatch(List<String> texts);

    /**
     * Vérifie la disponibilité du service d'embeddings.
     * Implémentation par défaut : retourne indisponible (override dans chaque client).
     */
    default ServiceStatus checkHealth() {
        return ServiceStatus.unavailable("embedding", "", 0);
    }
}
