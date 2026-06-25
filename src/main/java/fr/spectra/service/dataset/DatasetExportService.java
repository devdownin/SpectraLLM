package fr.spectra.service.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.dto.DatasetStats;
import fr.spectra.model.TrainingPair;
import fr.spectra.service.ChromaDbClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Export du dataset au format attendu par les outils d'entraînement.
 *
 * <p><b>Rôle.</b> {@link DatasetGeneratorService} produit et stocke les paires Q/R ; ce service
 * les met en forme pour la consommation : statistiques agrégées (répartition par catégorie /
 * type, confiance moyenne, couverture des chunks) affichées dans l'UI, et export <b>JSONL</b>
 * — un objet JSON par ligne, le format <i>de facto</i> des frameworks de fine-tuning (Unsloth,
 * Axolotl, TRL…). Chaque ligne devient un exemple d'entraînement.</p>
 *
 * <p>Séparer génération et export suit le principe de responsabilité unique : on peut
 * ré-exporter ou recalculer les stats sans relancer la génération (coûteuse en appels LLM).</p>
 */
@Service
public class DatasetExportService {

    private static final Logger log = LoggerFactory.getLogger(DatasetExportService.class);
    private static final String COLLECTION_NAME = "spectra_documents";
    private static final ObjectMapper mapper = new ObjectMapper();

    private final DatasetGeneratorService generatorService;
    private final ChromaDbClient chromaDbClient;
    private final Path datasetDir;

    public DatasetExportService(DatasetGeneratorService generatorService,
                                ChromaDbClient chromaDbClient,
                                @Value("${spectra.dataset.dir:./data/dataset}") String datasetDir) {
        this.generatorService = generatorService;
        this.chromaDbClient = chromaDbClient;
        this.datasetDir = Path.of(datasetDir);
    }

    /**
     * Statistiques sur le dataset généré.
     */
    public DatasetStats getStats() {
        List<TrainingPair> pairs = generatorService.getAllPairs();

        Map<String, Integer> byCategory = new HashMap<>();
        Map<String, Integer> byType = new HashMap<>();
        double totalConfidence = 0;

        for (TrainingPair pair : pairs) {
            byCategory.merge(pair.metadata().category(), 1, Integer::sum);
            byType.merge(pair.metadata().type(), 1, Integer::sum);
            totalConfidence += pair.metadata().confidence();
        }

        int chunksInStore = 0;
        try {
            String collectionId = chromaDbClient.getOrCreateCollection(COLLECTION_NAME);
            chunksInStore = chromaDbClient.count(collectionId);
        } catch (Exception e) {
            log.warn("Impossible de compter les chunks ChromaDB: {}", e.getMessage());
        }

        return new DatasetStats(
                pairs.size(),
                chunksInStore,
                byCategory,
                byType,
                pairs.isEmpty() ? 0 : totalConfidence / pairs.size()
        );
    }

    /**
     * Exporte le dataset en fichier JSONL.
     * Retourne le chemin du fichier généré.
     */
    public Path exportJsonl() throws IOException {
        List<TrainingPair> pairs = generatorService.getAllPairs();

        Files.createDirectories(datasetDir);
        Path outputFile = datasetDir.resolve("spectra_dataset_" + System.currentTimeMillis() + ".jsonl");

        try (BufferedWriter writer = Files.newBufferedWriter(outputFile)) {
            for (TrainingPair pair : pairs) {
                writer.write(mapper.writeValueAsString(pair));
                writer.newLine();
            }
        }

        log.info("Dataset exporté: {} paires → {}", pairs.size(), outputFile);
        return outputFile;
    }
}
