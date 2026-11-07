package fr.spectra.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(name = "spectra.mcp.enabled", havingValue = "true")
public class McpRequiresApiKeyFilter extends OncePerRequestFilter {
    private static final String SESSION = "Mcp-Session-Id";
    private static final String AUTHENTICATED = McpRequiresApiKeyFilter.class.getName() + ".scoped";
    private final boolean keyConfigured;
    private final byte[] sessionSigningKey;
    private final Map<String, McpCaller> scopedKeys;
    private final String endpoint;

    record CallerConfig(String key, Set<String> collections) {}

    @Autowired
    public McpRequiresApiKeyFilter(@Value("${SPECTRA_API_KEY:}") String apiKey,
                                   @Value("${SPECTRA_MCP_CALLERS_JSON:}") String callersJson,
                                   @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String endpoint) {
        keyConfigured = !apiKey.isBlank();
        sessionSigningKey = apiKey.getBytes(StandardCharsets.UTF_8);
        this.endpoint = endpoint;
        scopedKeys = parseCallers(callersJson, apiKey);
    }

    McpRequiresApiKeyFilter(String apiKey) {
        this(apiKey, "", "/mcp");
    }

    public static boolean hasAuthenticatedScopedCaller(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(AUTHENTICATED));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals(endpoint) && !path.startsWith(endpoint + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!keyConfigured) {
            response.setStatus(503);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Configure SPECTRA_API_KEY before exposing MCP\"}");
            return;
        }
        if (scopedKeys.isEmpty()) {
            request.setAttribute(McpCaller.ATTRIBUTE, McpCaller.LEGACY);
            chain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader("X-API-Key");
        McpCaller caller = null;
        if (provided != null) {
            for (var entry : scopedKeys.entrySet()) {
                if (sameSecret(entry.getKey(), provided)) caller = entry.getValue();
            }
        }
        if (caller == null) {
            response.sendError(401, "A scoped MCP key is required");
            return;
        }

        String callerId = caller.id();
        String incoming = request.getHeader(SESSION);
        String session = null;
        if (incoming != null) {
            int delimiter = incoming.lastIndexOf('.');
            if (delimiter <= 0 || !sameSecret(sign(callerId, incoming.substring(0, delimiter)), incoming)) {
                response.sendError(403, "Session is not accessible to this MCP caller");
                return;
            }
            session = incoming.substring(0, delimiter);
        }
        request.setAttribute(McpCaller.ATTRIBUTE, caller);
        request.setAttribute(AUTHENTICATED, true);
        String internalSession = session;
        var wrappedRequest = new HttpServletRequestWrapper(request) {
            @Override public String getHeader(String name) {
                return SESSION.equalsIgnoreCase(name) ? internalSession : super.getHeader(name);
            }
            @Override public java.util.Enumeration<String> getHeaders(String name) {
                return SESSION.equalsIgnoreCase(name)
                        ? java.util.Collections.enumeration(internalSession == null ? List.of() : List.of(internalSession))
                        : super.getHeaders(name);
            }
        };
        var wrappedResponse = new HttpServletResponseWrapper(response) {
            @Override public void setHeader(String name, String value) {
                super.setHeader(name, SESSION.equalsIgnoreCase(name) && value != null ? sign(callerId, value) : value);
            }
            @Override public void addHeader(String name, String value) {
                super.addHeader(name, SESSION.equalsIgnoreCase(name) && value != null ? sign(callerId, value) : value);
            }
        };
        chain.doFilter(wrappedRequest, wrappedResponse);
    }

    private static Map<String, McpCaller> parseCallers(String json, String apiKey) {
        if (json.isBlank()) return Map.of();
        if (apiKey.isBlank()) throw new IllegalArgumentException("Scoped MCP requires a separate SPECTRA_API_KEY for the REST API");
        Map<String, CallerConfig> entries;
        try {
            entries = new ObjectMapper().readValue(json, new TypeReference<>() {});
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid SPECTRA_MCP_CALLERS_JSON");
        }
        if (entries == null || entries.isEmpty()) throw new IllegalArgumentException("Scoped MCP requires at least one caller");
        var result = new LinkedHashMap<String, McpCaller>();
        entries.forEach((id, config) -> {
            if (!id.matches("[a-zA-Z0-9_-]{1,128}") || config == null || config.key() == null || config.key().isBlank()
                    || sameSecret(apiKey, config.key()) || config.collections() == null || config.collections().isEmpty()
                    || config.collections().stream().anyMatch(name -> name == null || name.isBlank())) {
                throw new IllegalArgumentException("Invalid scoped MCP caller configuration");
            }
            if (result.putIfAbsent(config.key(), new McpCaller(id, Set.copyOf(config.collections()))) != null) {
                throw new IllegalArgumentException("Scoped MCP keys must be distinct");
            }
        });
        return Map.copyOf(result);
    }

    private static boolean sameSecret(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String callerId, String session) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sessionSigningKey, "HmacSHA256"));
            return session + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal((callerId + "\n" + session).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Could not bind MCP session to its caller", e);
        }
    }
}
