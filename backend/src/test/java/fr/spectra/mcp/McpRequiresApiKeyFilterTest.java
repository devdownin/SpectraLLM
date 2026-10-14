package fr.spectra.mcp;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class McpRequiresApiKeyFilterTest {

    private static MockHttpServletResponse call(String apiKey, String path, FilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new McpRequiresApiKeyFilter(apiKey).doFilter(request, response, chain);
        return response;
    }

    @Test
    void withoutAKeyMcpAnswers503WithTheReason() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = call("", "/mcp", chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("SPECTRA_API_KEY");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void withoutAKeyTheRestOfTheApiIsLeftToApiKeyFilter() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        call("", "/api/query", chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void withAKeyMcpGoesThroughToTheApiKeyCheck() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        assertThat(call("secret", "/mcp", chain).getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }
}
