package fr.spectra.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * CORS configuration — nécessaire en développement (Vite :5173 → Spring :8080).
 * En production Docker, nginx proxyfie /api/ donc le CORS n'est pas sollicité.
 *
 * <p>Les origines autorisées sont configurables via {@code spectra.cors.allowed-origin-patterns}
 * (liste séparée par des virgules) afin de pouvoir restreindre — ou désactiver — le CORS
 * en production sans recompiler. Par défaut : localhost uniquement.</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOriginPatterns;
    private final AsyncTaskExecutor taskExecutor;

    public WebConfig(@Value("${spectra.cors.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}")
                     String allowedOriginPatterns,
                     AsyncTaskExecutor taskExecutor) {
        this.allowedOriginPatterns = allowedOriginPatterns.isBlank()
                ? new String[0]
                : allowedOriginPatterns.split("\\s*,\\s*");
        this.taskExecutor = taskExecutor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(taskExecutor);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOriginPatterns.length == 0) return; // CORS désactivé (ex. prod derrière nginx)
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Type", "X-Requested-With")
                .maxAge(3600);
    }
}
