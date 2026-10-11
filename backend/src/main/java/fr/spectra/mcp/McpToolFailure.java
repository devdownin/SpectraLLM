package fr.spectra.mcp;

import fr.spectra.service.ChromaDbClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Traduit une panne d'infrastructure en une phrase qu'un agent peut lire.
 *
 * <p>Spring AI renvoie à l'agent le message de l'exception levée par l'outil, suivi de celui de
 * sa cause racine. Une ChromaDB arrêtée donnait donc « finishConnect(..) failed with error(-111):
 * Connection refused: /127.0.0.1:1 » — exact, et inutilisable : rien ne dit quel service manque,
 * ni que le corpus n'a pas été lu. Un agent en conclut volontiers que la collection est vide.</p>
 *
 * <p>Spring AI écrit le message de l'exception puis celui de sa cause racine. L'exception levée
 * ici porte donc une cause <b>muette</b> plutôt que la panne d'origine — avec elle, le texte brut
 * qu'elle remplace reviendrait en seconde ligne ; sans cause, la phrase serait écrite deux fois.
 * La panne d'origine part dans le journal.</p>
 */
final class McpToolFailure extends RuntimeException {

    private static final Logger log = LoggerFactory.getLogger(McpToolFailure.class);

    static final String NOTHING_CONCLUDED = " Nothing was read from the corpus, so this says nothing about "
            + "what it holds; spectra_status shows which service is down.";

    private McpToolFailure(String message) {
        super(message, Silent.INSTANCE, false, false);
    }

    /** Cause racine sans message : ce que Spring AI ajoute après la phrase est alors vide. */
    static final class Silent extends Throwable {
        static final Silent INSTANCE = new Silent();

        private Silent() {
            super("", null, false, false);
        }
    }

    /** Exécute {@code work} et remplace une panne d'infrastructure par un message lisible. */
    static <T> T guard(String tool, Supplier<T> work) {
        try {
            return work.get();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            String described = describe(e);
            if (described == null) {
                throw e;
            }
            log.warn("Outil MCP {} : {}", tool, described, e);
            throw new McpToolFailure(described);
        }
    }

    /** La phrase pour {@code e}, ou {@code null} si ce n'est pas une panne que l'on sait nommer. */
    static String describe(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof NoSuchElementException) {
                return "Not found: " + t.getMessage() + ".";
            }
            if (t instanceof ChromaDbClient.EmbeddingModelMismatchException) {
                return "The collection was indexed with another embedding model and cannot be searched "
                        + "until it is re-indexed: " + t.getMessage();
            }
            if (t instanceof WebClientRequestException request) {
                return "The service at " + origin(request.getUri()) + " did not answer ("
                        + reason(request) + ")." + NOTHING_CONCLUDED;
            }
            if (t instanceof WebClientResponseException response) {
                return "The service at " + origin(response.getRequest() == null ? null : response.getRequest().getURI())
                        + " answered HTTP " + response.getStatusCode().value() + "." + NOTHING_CONCLUDED;
            }
            if (t instanceof ChromaDbClient.ChromaDbUnavailableException) {
                return "The vector store (ChromaDB) is unavailable." + NOTHING_CONCLUDED;
            }
            if (t instanceof TimeoutException
                    || (t instanceof IllegalStateException && String.valueOf(t.getMessage()).startsWith("Timeout"))) {
                return "A service Spectra depends on did not answer in time." + NOTHING_CONCLUDED;
            }
        }
        return null;
    }

    private static String origin(URI uri) {
        if (uri == null) {
            return "an unknown address";
        }
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
    }

    /** « connection refused » plutôt que la ligne netty qui le dit en trois fois. */
    private static String reason(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = String.valueOf(root.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (message.contains("connection refused")) return "connection refused";
        if (message.contains("unknownhost") || root instanceof java.net.UnknownHostException) return "unknown host";
        if (message.contains("timed out") || message.contains("timeout")) return "timed out";
        return root.getClass().getSimpleName();
    }
}
