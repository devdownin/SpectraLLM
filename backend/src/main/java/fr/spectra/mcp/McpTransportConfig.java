package fr.spectra.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spectra.mcp.enabled", havingValue = "true")
@EnableConfigurationProperties(McpServerStreamableHttpProperties.class)
class McpTransportConfig {
    @Bean
    WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            @Qualifier("mcpServerJsonMapper") JsonMapper mapper, McpServerStreamableHttpProperties properties) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(mapper))
                .mcpEndpoint(properties.getMcpEndpoint())
                .keepAliveInterval(properties.getKeepAliveInterval())
                .disallowDelete(properties.isDisallowDelete())
                .contextExtractor(request -> request.servletRequest().getAttribute(McpCaller.ATTRIBUTE)
                        instanceof McpCaller caller ? caller.context() : McpTransportContext.EMPTY)
                .build();
    }
}
