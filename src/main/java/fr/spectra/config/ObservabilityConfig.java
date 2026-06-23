package fr.spectra.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration de l'observabilité (métriques Micrometer → /actuator/prometheus).
 *
 * <ul>
 *   <li>{@link TimedAspect} : active l'annotation {@link io.micrometer.core.annotation.Timed}
 *       sur les beans Spring (latence par méthode, ex. {@code RagService.query}).</li>
 *   <li>Tag commun {@code application=spectrallm} sur toutes les métriques, afin de
 *       distinguer l'app dans un Prometheus/Grafana multi-services. Ajouté via
 *       customizer (robuste aux changements de propriétés entre versions de Boot).</li>
 * </ul>
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    @Bean
    MeterRegistryCustomizer<MeterRegistry> commonMetricsTags() {
        return registry -> registry.config().commonTags("application", "spectrallm");
    }
}
