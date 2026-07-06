package fr.spectra.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contrôle de cohérence embedding ↔ index vectoriel.
 *
 * <p><b>Pourquoi.</b> Les vecteurs d'une collection ChromaDB ne sont comparables qu'entre
 * eux : si le modèle d'embedding actif change sans ré-ingestion, le RAG renvoie des résultats
 * aléatoires <i>sans aucune erreur</i>. Ce vérificateur détecte cette dérive au démarrage en
 * comparant l'estampille {@code spectra:embedding-model} de chaque collection (posée par
 * {@link ChromaDbClient} à la création) avec le modèle d'embedding actif du registre.</p>
 *
 * <p>Le contrôle est purement informatif au démarrage (l'application démarre quand même —
 * ChromaDB peut être encore indisponible à cet instant) ; le blocage effectif des lectures
 * et écritures incohérentes est assuré par {@link ChromaDbClient#getOrCreateCollection}.
 * Le rapport est réexécutable à la demande via {@code GET /api/config/embedding-consistency}.</p>
 */
@Service
public class EmbeddingConsistencyChecker {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConsistencyChecker.class);

    /** Statuts possibles d'une collection dans le rapport. */
    public static final String STATUS_OK = "OK";
    public static final String STATUS_MISMATCH = "MISMATCH";
    public static final String STATUS_UNSTAMPED = "UNSTAMPED";

    private final ChromaDbClient chromaDbClient;
    private final ModelRegistryService modelRegistry;

    public EmbeddingConsistencyChecker(ChromaDbClient chromaDbClient, ModelRegistryService modelRegistry) {
        this.chromaDbClient = chromaDbClient;
        this.modelRegistry = modelRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        try {
            verify();
        } catch (Exception e) {
            // Ne jamais empêcher le démarrage : ChromaDB peut être encore en cours de boot.
            log.warn("Contrôle de cohérence embedding impossible au démarrage : {}", e.getMessage());
        }
    }

    /**
     * Compare l'estampille embedding de chaque collection au modèle actif.
     *
     * @return le rapport par collection : {@code name}, {@code status} (OK | MISMATCH |
     *         UNSTAMPED), {@code indexedWith} (estampille, absente si UNSTAMPED)
     */
    public Map<String, Object> verify() {
        String active = modelRegistry.getActiveEmbeddingModel();
        List<Map<String, Object>> collections = new ArrayList<>();
        int mismatches = 0;

        for (Map<String, Object> collection : chromaDbClient.listCollections()) {
            String name = String.valueOf(collection.get("name"));
            String stamped = extractStamp(collection);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            if (stamped == null || stamped.isBlank()) {
                entry.put("status", STATUS_UNSTAMPED);
            } else {
                entry.put("indexedWith", stamped);
                boolean ok = stamped.equals(active);
                entry.put("status", ok ? STATUS_OK : STATUS_MISMATCH);
                if (!ok) {
                    mismatches++;
                    log.error("Collection '{}' indexée avec le modèle d'embedding '{}' mais le modèle "
                            + "actif est '{}'. Le RAG sur cette collection est bloqué : réactivez '{}' "
                            + "ou ré-ingérez la collection.", name, stamped, active, stamped);
                }
            }
            collections.add(entry);
        }

        if (mismatches == 0) {
            log.info("Cohérence embedding ↔ index : {} collection(s) vérifiée(s), aucune incohérence "
                    + "(modèle actif : '{}')", collections.size(), active);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("activeEmbeddingModel", active);
        report.put("mismatches", mismatches);
        report.put("collections", collections);
        return report;
    }

    private static String extractStamp(Map<String, Object> collection) {
        Object metadata = collection.get("metadata");
        if (metadata instanceof Map<?, ?> meta) {
            Object stamped = meta.get(ChromaDbClient.EMBEDDING_MODEL_METADATA_KEY);
            return stamped != null ? stamped.toString() : null;
        }
        return null;
    }
}
