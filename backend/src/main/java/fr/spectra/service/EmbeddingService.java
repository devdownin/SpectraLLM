package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service métier pour la génération d'embeddings.
 * Délègue l'exécution technique au {@link EmbeddingClient} actif (Ollama ou llama.cpp).
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingClient client;

    public EmbeddingService(EmbeddingClient client) {
        this.client = client;
    }

    /**
     * Génère un vecteur d'embedding pour un texte donné.
     */
    public List<Float> embed(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return client.embed(text);
    }

    /**
     * Génère des embeddings pour une liste de textes.
     */
    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return client.embedBatch(texts);
    }
}
