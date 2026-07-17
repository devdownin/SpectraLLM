package fr.spectra.service;

import fr.spectra.dto.IngestionTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Orchestre l'ingestion de documents à partir d'URLs distantes.
 * Supporte les pages HTML statiques et dynamiques (via browserless) ainsi que les PDFs.
 * Les tâches sont enregistrées dans le registre partagé d'IngestionService pour
 * être accessibles via GET /api/ingest/{taskId}.
 */
@Service
public class UrlIngestionService {

    private static final Logger log = LoggerFactory.getLogger(UrlIngestionService.class);

    private final IngestionService ingestionService;
    private final UrlFetcherService urlFetcher;
    private final ChromaDbClient chromaDbClient;
    private final String defaultCollection;

    public UrlIngestionService(IngestionService ingestionService,
                               UrlFetcherService urlFetcher,
                               ChromaDbClient chromaDbClient,
                               @Value("${spectra.chromadb.collection:spectra_documents}") String defaultCollection) {
        this.ingestionService = ingestionService;
        this.urlFetcher = urlFetcher;
        this.chromaDbClient = chromaDbClient;
        this.defaultCollection = defaultCollection;
    }

    /**
     * Lance l'ingestion asynchrone d'une liste d'URLs.
     * Retourne immédiatement avec une tâche en état PENDING.
     */
    public IngestionTask submit(List<String> urls) {
        String taskId = UUID.randomUUID().toString();
        IngestionTask task = ingestionService.registerTask(taskId, urls);

        Thread.ofVirtual().name("url-ingest-" + taskId.substring(0, 8)).start(() -> {
            ingestionService.updateTask(taskId, ingestionService.getTask(taskId).processing());
            int totalChunks = 0;
            // Erreurs par URL : conservées même en cas de succès partiel — la tâche finissait
            // COMPLETED en jetant lastError dès qu'une seule URL avait produit des chunks.
            java.util.List<String> urlErrors = new java.util.ArrayList<>();

            try {
                // Vérification fail-fast de la collection avant de télécharger quoi que ce soit.
                chromaDbClient.getOrCreateCollection(defaultCollection);
                for (String url : urls) {
                    log.info("Ingestion URL: {}", url);
                    try {
                        UrlFetcherService.FetchedContent content = urlFetcher.fetch(url);
                        int chunks = ingestionService.ingest(content.filename(), content.inputStream(), defaultCollection);
                        totalChunks += chunks;
                        log.info("URL '{}' ingérée → {} chunks", url, chunks);
                    } catch (Exception e) {
                        log.error("Erreur ingestion URL '{}': {}", url, e.getMessage());
                        urlErrors.add(url + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                urlErrors.add(e.getMessage());
                log.error("Erreur collection ChromaDB pour URL ingestion: {}", e.getMessage());
            }

            IngestionTask current = ingestionService.getTask(taskId).withFileErrors(urlErrors);
            if (totalChunks == 0 && !urlErrors.isEmpty()) {
                ingestionService.updateTask(taskId, current.failed(String.join(" ; ", urlErrors)));
            } else {
                ingestionService.updateTask(taskId, current.completed(totalChunks));
            }
        });

        return task;
    }
}
