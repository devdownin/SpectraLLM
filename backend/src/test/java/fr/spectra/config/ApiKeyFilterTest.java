package fr.spectra.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Résolution multi-sources de la clé API : header, paramètre de requête et cookie.
 * Le paramètre et le cookie existent pour EventSource (SSE), incapable d'envoyer un en-tête.
 */
class ApiKeyFilterTest {

    private static final String KEY = "s3cr3t-key";

    private final ApiKeyFilter filter = new ApiKeyFilter(KEY);

    @Test
    void headerKey_isAccepted() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/health");
        req.addHeader("X-API-Key", KEY);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void queryParamKey_isAccepted_forEventSource() throws Exception {
        // EventSource ne peut pas poser d'en-tête → il passe la clé en paramètre d'URL.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/models/hub/install/progress");
        req.setParameter("apiKey", KEY);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
    }

    @Test
    void cookieKey_isAccepted() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/query/stream");
        req.setCookies(new Cookie("X-API-Key", KEY));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
    }

    @Test
    void blankHeader_fallsBackToQueryParam() throws Exception {
        // Header présent mais vide → on doit basculer sur le paramètre (branche isBlank()).
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/query/stream");
        req.addHeader("X-API-Key", "");
        req.setParameter("apiKey", KEY);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
    }

    @Test
    void blankQueryParam_fallsBackToCookie() throws Exception {
        // Paramètre présent mais vide → on doit basculer sur le cookie.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/query/stream");
        req.setParameter("apiKey", "");
        req.setCookies(new Cookie("X-API-Key", KEY));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
    }

    @Test
    void missingKey_isRejectedWith401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/health");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void wrongKey_isRejectedWith401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/health");
        req.addHeader("X-API-Key", "mauvaise-cle");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void noKeyConfigured_allowsEverything() throws Exception {
        ApiKeyFilter openFilter = new ApiKeyFilter("");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/health");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse res = new MockHttpServletResponse();

        openFilter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
    }

    @Test
    void actuator_isExemptEvenWithoutKey() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        // shouldNotFilter court-circuite le filtre : la requête passe sans clé.
        verify(chain).doFilter(req, res);
    }
}
