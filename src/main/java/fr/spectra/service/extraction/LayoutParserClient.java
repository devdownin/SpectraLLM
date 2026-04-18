package fr.spectra.service.extraction;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.ServiceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP client for the Python docparser microservice.
 * Sends a PDF file and receives structured Markdown text with preserved layout.
 *
 * <p>Active only when {@code spectra.layout-parser.enabled=true}.
 */
@Service
@ConditionalOnProperty(prefix = "spectra.layout-parser", name = "enabled", havingValue = "true")
public class LayoutParserClient {

    private static final Logger log = LoggerFactory.getLogger(LayoutParserClient.class);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final Duration timeout;
    private final String baseUrl;

    public record ParseResult(
            String text,
            int pageCount,
            Map<String, String> metadata,
            String parser
    ) {}

    public LayoutParserClient(@Qualifier("docParserWebClient") WebClient webClient,
                               SpectraProperties props) {
        this.webClient = webClient;
        int seconds = props.layoutParser() != null
                ? props.layoutParser().effectiveTimeoutSeconds()
                : 120;
        this.timeout = Duration.ofSeconds(seconds);
        this.baseUrl = props.layoutParser() != null
                ? props.layoutParser().effectiveBaseUrl()
                : "http://docparser:8001";
    }

    public ServiceStatus checkHealth() {
        long start = System.currentTimeMillis();
        try {
            webClient.get()
                    .uri("/health")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(HEALTH_TIMEOUT);
            long elapsed = System.currentTimeMillis() - start;
            return new ServiceStatus("docparser", baseUrl, true, "ok", elapsed, Map.of());
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("docparser indisponible: {}", e.getMessage());
            return ServiceStatus.unavailable("docparser", baseUrl, elapsed);
        }
    }

    /**
     * Send {@code fileName} + {@code content} bytes to the docparser service.
     *
     * @return parsed result, or empty if the service is unavailable or returns an error
     */
    @SuppressWarnings("unchecked")
    public Optional<ParseResult> parse(String fileName, byte[] content) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() { return fileName; }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);

        try {
            Map<String, Object> response = webClient.post()
                    .uri("/parse")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(timeout);

            if (response == null) {
                log.warn("Null response from docparser for '{}'", fileName);
                return Optional.empty();
            }

            String text      = (String) response.getOrDefault("text", "");
            int    pageCount = response.get("page_count") instanceof Number n ? n.intValue() : 0;
            Map<String, String> metadata = (Map<String, String>) response.getOrDefault("metadata", Map.of());
            String parser    = (String) response.getOrDefault("parser", "unknown");

            log.debug("docparser result for '{}': {} chars, {} pages, parser={}",
                    fileName, text.length(), pageCount, parser);
            return Optional.of(new ParseResult(text, pageCount, metadata, parser));

        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            log.warn("docparser service error for '{}' ({}): {}", fileName, e.getStatusCode(), e.getResponseBodyAsString());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("docparser call failed for '{}': {}", fileName, e.getMessage());
            return Optional.empty();
        }
    }
}
