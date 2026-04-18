package fr.spectra.service;

import fr.spectra.dto.FineTuningRequest;
import fr.spectra.service.dataset.DatasetGeneratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Service orchestrant le mode batch : ingestion locale -> génération dataset -> fine-tuning.
 */
@Service
public class BatchService {

    private static final Logger log = LoggerFactory.getLogger(BatchService.class);

    private final IngestionService ingestionService;
    private final DatasetGeneratorService datasetGeneratorService;
    private final FineTuningService fineTuningService;

    @Value("${spectra.batch.source-dir:./data/source}")
    private String sourceDir;

    @Value("${spectra.batch.model-name:spectra-domain}")
    private String defaultModelName;

    public BatchService(IngestionService ingestionService,
                        DatasetGeneratorService datasetGeneratorService,
                        FineTuningService fineTuningService) {
        this.ingestionService = ingestionService;
        this.datasetGeneratorService = datasetGeneratorService;
        this.fineTuningService = fineTuningService;
    }

    @Async
    public void runBatchProcess() {
        log.info("Démarrage du processus batch...");
        Path sourcePath = Path.of(sourceDir);

        if (!Files.exists(sourcePath)) {
            log.error("Le répertoire source n'existe pas : {}", sourceDir);
            return;
        }

        try {
            // 1. Lister les fichiers
            List<Path> filesToIngest;
            try (Stream<Path> paths = Files.walk(sourcePath)) {
                filesToIngest = paths
                        .filter(Files::isRegularFile)
                        .filter(p -> isSupported(p.getFileName().toString()))
                        .toList();
            }

            if (filesToIngest.isEmpty()) {
                log.info("Aucun fichier supporté trouvé dans {}", sourceDir);
                return;
            }

            log.info("{} fichiers trouvés pour ingestion.", filesToIngest.size());

            // 2. Ingestion
            int chunksCount = ingestionService.ingestLocalFiles(filesToIngest);
            log.info("Ingestion terminée : {} chunks générés.", chunksCount);

            if (chunksCount == 0) {
                log.warn("Aucun contenu n'a pu être extrait. Fin du batch.");
                return;
            }

            // 3. Génération Dataset
            String datasetTaskId = UUID.randomUUID().toString();
            int pairsCount = datasetGeneratorService.generate(datasetTaskId, 0);
            log.info("Génération du dataset terminée : {} paires créées.", pairsCount);

            if (pairsCount == 0) {
                log.warn("Le dataset est vide. Fin du batch.");
                return;
            }

            // 4. Fine-tuning
            FineTuningRequest request = new FineTuningRequest(defaultModelName, null, null, null, null, null, null, null, null);
            String jobId = fineTuningService.submit(request);
            log.info("Lancement du fine-tuning (Job ID: {})...", jobId);

            log.info("Processus batch terminé avec succès ! Modèle généré : {}", defaultModelName);

        } catch (Exception e) {
            log.error("Erreur fatale durant le processus batch : {}", e.getMessage(), e);
        }
    }

    private boolean isSupported(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".doc")
                || lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".txt");
    }
}
