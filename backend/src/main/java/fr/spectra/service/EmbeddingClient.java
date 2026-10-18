package fr.spectra.service;

import fr.spectra.dto.ServiceStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Abstraction du provider d'embeddings.
 */
public interface EmbeddingClient {

    List<Float> embed(String text);

    List<List<Float>> embedBatch(List<String> texts);

    /**
     * Nombre de tokens de chaque texte selon le tokenizer <b>du modèle servi</b>, tokens spéciaux
     * compris — la seule mesure qui dise si un texte tient dans la fenêtre d'embedding. Le
     * chunking compte en cl100k, un vocabulaire étranger au modèle : sur du français, WordPiece
     * (nomic) produit sensiblement plus de tokens.
     *
     * @return vide si le fournisseur ne sait pas tokeniser ; l'appelant ne vérifie alors rien
     */
    default Optional<List<Integer>> countTokens(List<String> texts) {
        return Optional.empty();
    }

    /**
     * Vérifie la disponibilité du service d'embeddings.
     * Implémentation par défaut : retourne indisponible (override dans chaque client).
     */
    default ServiceStatus checkHealth() {
        return ServiceStatus.unavailable("embedding", "", 0);
    }
}
