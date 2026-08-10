package fr.spectra.util;

import fr.spectra.dto.ServiceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Sondage {@code /health} d'un service HTTP interne.
 *
 * <p>Le corps de {@code checkHealth()} était identique dans {@code CrossEncoderRerankerClient}
 * et {@code LayoutParserClient} — seule l'étiquette du service différait. Voir
 * {@code docs/process/audit-simplification.fr.md} (S3).
 */
public final class HealthProbe {

    private static final Logger log = LoggerFactory.getLogger(HealthProbe.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private HealthProbe() {}

    public static ServiceStatus probe(WebClient webClient, String serviceName, String baseUrl) {
        return probe(webClient, serviceName, baseUrl, DEFAULT_TIMEOUT);
    }

    /**
     * Interroge {@code /health} et renvoie l'état du service, latence comprise.
     *
     * <p>Toute exception vaut « indisponible » : un sondage de santé qui propagerait l'erreur
     * ferait échouer l'appelant — or son rôle est précisément de rapporter l'indisponibilité,
     * pas d'en souffrir.
     */
    @SuppressWarnings("rawtypes")
    public static ServiceStatus probe(WebClient webClient, String serviceName, String baseUrl,
                                      Duration timeout) {
        long start = System.currentTimeMillis();
        try {
            webClient.get().uri("/health").retrieve().bodyToMono(Map.class).block(timeout);
            return new ServiceStatus(serviceName, baseUrl, true, "ok",
                    System.currentTimeMillis() - start, Map.of());
        } catch (Exception e) {
            log.warn("{} indisponible: {}", serviceName, e.getMessage());
            return ServiceStatus.unavailable(serviceName, baseUrl,
                    System.currentTimeMillis() - start);
        }
    }
}
