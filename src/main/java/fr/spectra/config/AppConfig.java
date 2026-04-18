package fr.spectra.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties(SpectraProperties.class)
@EnableScheduling
public class AppConfig {

    @Bean
    public WebClient llmWebClient(SpectraProperties props) {
        String baseUrl = props.llm() != null && props.llm().baseUrl() != null
                ? props.llm().baseUrl()
                : "http://llm-server:8081";
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public WebClient chromaDbWebClient(SpectraProperties props) {
        String baseUrl = props.chromadb() != null && props.chromadb().baseUrl() != null
                ? props.chromadb().baseUrl()
                : "http://chromadb:8000";
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public WebClient llamaCppChatWebClient(SpectraProperties props) {
        SpectraProperties.EndpointProperties chat = props.llm() != null ? props.llm().chat() : null;
        SpectraProperties.RuntimeProperties runtime = props.llm() != null ? props.llm().runtime() : null;
        String baseUrl = runtime != null && runtime.enabled()
                ? "http://127.0.0.1:" + runtime.effectivePort()
                : chat != null && chat.baseUrl() != null
                    ? chat.baseUrl()
                    : "http://llama-cpp-chat:8080";
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public WebClient llamaCppEmbeddingWebClient(SpectraProperties props) {
        SpectraProperties.EndpointProperties embedding = props.llm() != null ? props.llm().embedding() : null;
        String baseUrl = embedding != null && embedding.baseUrl() != null
                ? embedding.baseUrl()
                : "http://llama-cpp-embed:8080";
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public WebClient rerankerWebClient(SpectraProperties props) {
        String baseUrl = props.reranker() != null
                ? props.reranker().effectiveBaseUrl()
                : "http://reranker:8000";
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public WebClient docParserWebClient(SpectraProperties props) {
        String baseUrl = props.layoutParser() != null
                ? props.layoutParser().effectiveBaseUrl()
                : "http://docparser:8001";
        int bufferBytes = (props.layoutParser() != null
                ? props.layoutParser().effectiveBufferSizeMb() : 100) * 1024 * 1024;
        return WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(bufferBytes))
                .build();
    }
}
