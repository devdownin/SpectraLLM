package fr.spectra.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

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
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);
    private static final String HEADER = "X-API-Key";

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
        String provided = request.getHeader(HEADER);
        if (!expectedKey.equals(provided)) {
            log.warn("Requête rejetée — clé API invalide ou absente (URI={})", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Clé API invalide ou manquante — header X-API-Key requis\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
