package fr.spectra.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Plafond de tâches @Async simultanées. Sans limite, un afflux de requêtes
     * (ingestion, évaluation, fine-tuning) crée un nombre illimité de threads
     * virtuels → épuisement mémoire/CPU et du pool de connexions H2.
     * Au-delà du plafond, la soumission bloque brièvement (backpressure).
     */
    @Bean
    public Executor taskExecutor(@Value("${spectra.async.concurrency-limit:50}") int concurrencyLimit) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("spectra-async-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(Math.max(1, concurrencyLimit));
        return executor;
    }
}
