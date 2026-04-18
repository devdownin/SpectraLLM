package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/**
 * Récupère le contenu d'une URL distante.
 * Les pages HTML sont rendues via browserless/chrome pour supporter les pages dynamiques (JS).
 * Les autres types (PDF, TXT, etc.) sont téléchargés directement.
 */
@Service
public class UrlFetcherService {

    private static final Logger log = LoggerFactory.getLogger(UrlFetcherService.class);
    private static final Duration HEAD_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration BROWSERLESS_TIMEOUT = Duration.ofSeconds(60);

    private final WebClient webClient;
    private final String browserlessUrl;

    public UrlFetcherService(WebClient.Builder webClientBuilder, SpectraProperties properties) {
        this.webClient = webClientBuilder
                .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024)) // 50 MB
                .build();
        this.browserlessUrl = properties.ingestion() != null
                ? properties.ingestion().effectiveBrowserlessUrl()
                : "http://browserless:3000";
    }

    /**
     * Récupère le contenu d'une URL.
     * Retourne le contenu brut et un nom de fichier dérivé de l'URL.
     */
    public FetchedContent fetch(String url) {
        String contentType = detectContentType(url);
        if ("text/html".equals(contentType)) {
            return fetchHtmlViaBrowserless(url);
        }
        return fetchDirectly(url, contentType);
    }

    /**
     * Détecte le content-type via une requête HEAD.
     * Retourne "text/html" par défaut si la requête échoue ou si le type n'est pas reconnu.
     */
    private String detectContentType(String url) {
        try {
            var response = webClient.head()
                    .uri(URI.create(url))
                    .retrieve()
                    .toBodilessEntity()
                    .block(HEAD_TIMEOUT);
            if (response != null) {
                String ct = response.getHeaders().getFirst("Content-Type");
                if (ct != null) {
                    String base = ct.split(";")[0].trim().toLowerCase();
                    if (base.equals("application/pdf")
                            || base.equals("text/plain")
                            || base.contains("msword")
                            || base.contains("officedocument")) {
                        return base;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("HEAD request failed for {}: {}", url, e.getMessage());
        }
        return "text/html";
    }

    /**
     * Rend la page HTML via browserless pour exécuter le JavaScript.
     */
    private FetchedContent fetchHtmlViaBrowserless(String url) {
        log.info("Fetching HTML via browserless: {}", url);
        try {
            byte[] bytes = webClient.get()
                    .uri(browserlessUrl + "/content?url=" + URI.create(url).toASCIIString())
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(BROWSERLESS_TIMEOUT);
            return new FetchedContent(deriveFilename(url, ".html"), toStream(bytes));
        } catch (Exception e) {
            log.warn("browserless indisponible, téléchargement direct: {}", e.getMessage());
            return fetchDirectly(url, "text/html");
        }
    }

    /**
     * Télécharge le contenu directement (PDF, TXT, etc.) ou HTML sans browserless.
     */
    private FetchedContent fetchDirectly(String url, String contentType) {
        log.info("Fetching directly ({}): {}", contentType, url);
        byte[] bytes = webClient.get()
                .uri(URI.create(url))
                .retrieve()
                .bodyToMono(byte[].class)
                .block(FETCH_TIMEOUT);
        String ext = extensionForContentType(contentType);
        return new FetchedContent(deriveFilename(url, ext), toStream(bytes));
    }

    private String deriveFilename(String url, String defaultExt) {
        try {
            String path = URI.create(url).getPath();
            if (path != null && !path.isBlank()) {
                String last = path.substring(path.lastIndexOf('/') + 1);
                if (!last.isBlank() && last.contains(".")) return last;
            }
        } catch (Exception ignored) {}
        try {
            String host = URI.create(url).getHost().replace(".", "_");
            return host + defaultExt;
        } catch (Exception ignored) {}
        return "url_content" + defaultExt;
    }

    private String extensionForContentType(String contentType) {
        return switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "text/plain" -> ".txt";
            case "text/html" -> ".html";
            default -> ".bin";
        };
    }

    private InputStream toStream(byte[] bytes) {
        return new ByteArrayInputStream(bytes != null ? bytes : new byte[0]);
    }

    public record FetchedContent(String filename, InputStream inputStream) {}
}
