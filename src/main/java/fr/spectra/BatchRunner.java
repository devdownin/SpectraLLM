package fr.spectra;

import fr.spectra.service.BatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class BatchRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchRunner.class);

    private final BatchService batchService;

    @Value("${spectra.batch.enabled:false}")
    private boolean batchEnabled;

    public BatchRunner(BatchService batchService) {
        this.batchService = batchService;
    }

    @Override
    public void run(String... args) {
        if (batchEnabled) {
            log.info("Mode Batch détecté (spectra.batch.enabled=true). Exécution de la pipeline complète.");
            batchService.runBatchProcess();
        } else {
            log.info("Mode Batch désactivé. L'application démarre normalement.");
        }
    }
}
