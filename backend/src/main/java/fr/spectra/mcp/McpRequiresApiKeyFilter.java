package fr.spectra.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Ferme {@code /mcp} quand aucune clé API n'est configurée.
 *
 * <p>{@link fr.spectra.config.ApiKeyFilter} laisse tout passer sans {@code SPECTRA_API_KEY}
 * (mode développement). Acceptable pour l'interface, pas pour un endpoint qu'un agent appelle
 * en boucle et qui rend le texte du corpus : activer MCP sans clé répond donc 503 avec la
 * raison, plutôt que d'ouvrir le corpus en silence. Avec une clé, c'est {@code ApiKeyFilter}
 * qui authentifie {@code /mcp} comme le reste de l'API.</p>
 */
@Component
@ConditionalOnProperty(name = "spectra.mcp.enabled", havingValue = "true")
public class McpRequiresApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(McpRequiresApiKeyFilter.class);
    static final String MCP_PATH = "/mcp";

    private final boolean keyConfigured;

    public McpRequiresApiKeyFilter(@Value("${SPECTRA_API_KEY:}") String apiKey) {
        this.keyConfigured = !apiKey.isBlank();
        if (!keyConfigured) {
            log.warn("spectra.mcp.enabled=true sans SPECTRA_API_KEY — /mcp répondra 503 tant qu'aucune clé n'est configurée");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return keyConfigured || !(path.equals(MCP_PATH) || path.startsWith(MCP_PATH + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"MCP est activé mais SPECTRA_API_KEY est vide : "
                + "configurez une clé pour exposer /mcp (header X-API-Key)\"}");
    }
}
