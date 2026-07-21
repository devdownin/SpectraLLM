package fr.spectra.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Filtre d'authentification par clé API (header {@code X-API-Key}).
 *
 * <p>Activé uniquement si la variable d'environnement {@code SPECTRA_API_KEY}
 * (ou la propriété Spring du même nom) est non vide. Si elle est absente ou
 * vide, toutes les requêtes sont acceptées (mode développement).</p>
 *
 * <p>Chemins exemptés (même si la clé est configurée) :</p>
 * <ul>
 *   <li>{@code /actuator/**} — healthchecks Docker / Kubernetes</li>
 *   <li>{@code /swagger-ui/**} et {@code /api-docs/**} — documentation OpenAPI</li>
 * </ul>
 *
 * <p><b>Sources de la clé.</b> La clé est lue, dans l'ordre, depuis le header {@code X-API-Key},
 * puis le paramètre de requête {@code apiKey}, puis le cookie {@code X-API-Key}. Le header reste
 * le canal privilégié ; le paramètre et le cookie existent parce que l'API navigateur
 * {@code EventSource} (SSE) <b>ne peut pas envoyer d'en-tête personnalisé</b> — sans eux, activer
 * {@code SPECTRA_API_KEY} coupait tous les flux SSE ouverts via {@code EventSource} (ex. la
 * progression d'installation de modèle). ⚠️ Un paramètre de requête peut apparaître dans les
 * journaux d'accès et l'en-tête {@code Referer} : préférez le header (ou le cookie) hors SSE.</p>
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);
    private static final String HEADER = "X-API-Key";
    /** Paramètre de requête de repli — pour EventSource, qui ne peut pas poser d'en-tête. */
    private static final String QUERY_PARAM = "apiKey";
    /** Cookie de repli — même raison, sans exposer la clé dans l'URL. */
    private static final String COOKIE = "X-API-Key";

    private final String expectedKey;

    public ApiKeyFilter(@Value("${SPECTRA_API_KEY:}") String apiKey) {
        this.expectedKey = apiKey.isBlank() ? null : apiKey;
        if (expectedKey == null) {
            log.warn("SPECTRA_API_KEY non configurée — authentification API désactivée (mode développement)");
        } else {
            log.info("Authentification API activée (X-API-Key)");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (expectedKey == null) {
            chain.doFilter(request, response);
            return;
        }
        String provided = resolveProvidedKey(request);
        if (!constantTimeEquals(expectedKey, provided)) {
            log.warn("Requête rejetée — clé API invalide ou absente (URI={})", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Clé API invalide ou manquante — "
                    + "header X-API-Key (ou paramètre apiKey / cookie X-API-Key pour EventSource)\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Résout la clé fournie : header {@code X-API-Key}, sinon paramètre {@code apiKey}, sinon
     * cookie {@code X-API-Key}. Le premier canal non vide gagne — un EventSource passe par le
     * paramètre ou le cookie faute de pouvoir poser un en-tête.
     */
    private static String resolveProvidedKey(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && !header.isBlank()) {
            return header;
        }
        String param = request.getParameter(QUERY_PARAM);
        if (param != null && !param.isBlank()) {
            return param;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE.equals(cookie.getName()) && cookie.getValue() != null
                        && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    /** Comparaison à temps constant pour éviter une fuite de la clé par canal temporel. */
    private static boolean constantTimeEquals(String expected, String provided) {
        if (provided == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
