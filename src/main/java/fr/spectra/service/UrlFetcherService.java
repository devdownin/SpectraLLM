package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.netty.http.client.HttpClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Set;

/**
 * Récupère le contenu d'une URL distante.
 * Les pages HTML sont rendues via browserless/chrome pour supporter les pages dynamiques (JS).
 * Les autres types (PDF, TXT, etc.) sont téléchargés directement.
 */
@Service
public class UrlFetcherService {

    private static final Logger log = LoggerFactory.getLogger(UrlFetcherService.class);
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Duration HEAD_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration BROWSERLESS_TIMEOUT = Duration.ofSeconds(60);

    /** Client de confiance pour appeler le service interne browserless. */
    private final WebClient webClient;
    /**
     * Client pour les fetch directs d'URL <em>utilisateur</em> : son résolveur valide
     * l'adresse IP réellement utilisée pour la connexion, fermant la fenêtre de
     * DNS-rebinding (TOCTOU) entre {@link #validateUrl} et le fetch.
     */
    private final WebClient fetchWebClient;
    private final String browserlessUrl;

    public UrlFetcherService(WebClient.Builder webClientBuilder, SpectraProperties properties) {
        this.webClient = webClientBuilder
                .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024)) // 50 MB
                .build();

        // HttpClient qui valide l'adresse résolue AU MOMENT DE LA CONNEXION : quelle que
        // soit l'IP à laquelle le nom d'hôte se résout réellement, on refuse les adresses
        // internes/privées — même si la résolution diffère de celle de validateUrl().
        HttpClient validatingHttpClient = HttpClient.create()
                .followRedirect(false) // pas de suivi 30x → limite le SSRF via Location
                .doAfterResolve((connection, remoteAddress) -> {
                    if (remoteAddress instanceof InetSocketAddress isa
                            && isa.getAddress() != null
                            && isForbiddenAddress(isa.getAddress())) {
                        throw new IllegalStateException(
                                "Accès à une adresse interne/privée interdit (SSRF) : "
                                        + isa.getAddress().getHostAddress());
                    }
                });
        // clientConnector() renvoie null quand le builder est un mock (tests unitaires) qui ne
        // le stubbe pas : on retombe alors sur le client principal (aucun réseau réel n'est
        // atteint). En production, le connecteur validant est bien appliqué.
        WebClient.Builder validatingBuilder =
                webClientBuilder.clientConnector(new ReactorClientHttpConnector(validatingHttpClient));
        this.fetchWebClient = validatingBuilder != null
                ? validatingBuilder.codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024)).build()
                : this.webClient;

        this.browserlessUrl = properties.ingestion() != null
                ? properties.ingestion().effectiveBrowserlessUrl()
                : "http://browserless:3000";
    }

    /**
     * Récupère le contenu d'une URL.
     * Retourne le contenu brut et un nom de fichier dérivé de l'URL.
     */
    public FetchedContent fetch(String url) {
        validateUrl(url);
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
            var response = fetchWebClient.head()
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
     *
     * <p>Note SSRF : browserless résout et charge l'URL <em>côté serveur</em> ; le
     * pré-contrôle {@link #validateUrl} reste advisory pour ce chemin. La défense
     * effective ici relève d'une politique d'egress réseau sur le conteneur browserless
     * (le rebinding sur le fetch direct est, lui, bloqué par {@link #fetchWebClient}).</p>
     */
    private FetchedContent fetchHtmlViaBrowserless(String url) {
        log.info("Fetching HTML via browserless: {}", url);
        try {
            byte[] bytes = webClient.get()
                    .uri(UriComponentsBuilder.fromUriString(browserlessUrl + "/content")
                            .queryParam("url", url)
                            .build()
                            .toUri())
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
        byte[] bytes = fetchWebClient.get()
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
            case "application/msword" -> ".doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            default -> ".bin";
        };
    }

    private InputStream toStream(byte[] bytes) {
        return new ByteArrayInputStream(bytes != null ? bytes : new byte[0]);
    }

    /**
     * Valide le schéma <em>et</em> l'hôte cible d'une URL avant tout fetch (protection SSRF).
     *
     * <p>Rejette les adresses loopback, any-local, link-local (169.254/16, fe80::),
     * site-local privées (10/8, 172.16/12, 192.168/16), multicast, CGNAT (100.64/10)
     * et IPv6 unique-local (fc00::/7) — empêchant l'accès aux métadonnées cloud
     * (169.254.169.254) et aux services internes.</p>
     *
     * <p>Note : Reactor Netty ne suit pas les redirections par défaut, ce qui limite
     * le SSRF via {@code Location} 30x.</p>
     */
    private void validateUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("URL invalide : " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new IllegalArgumentException("Schéma URL non autorisé : " + scheme + ". Seuls http et https sont acceptés.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Hôte URL manquant : " + url);
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (isForbiddenAddress(addr)) {
                    throw new IllegalArgumentException(
                            "Accès à une adresse interne/privée interdit (SSRF) : " + host + " → " + addr.getHostAddress());
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Hôte URL non résolvable : " + host);
        }
    }

    private static boolean isForbiddenAddress(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF, second = b[1] & 0xFF;
            return first == 100 && second >= 64 && second <= 127;   // 100.64.0.0/10 (CGNAT)
        }
        return (b[0] & 0xFE) == 0xFC;                                // fc00::/7 (IPv6 unique-local)
    }

    public record FetchedContent(String filename, InputStream inputStream) {}
}
