package fr.spectra.config;

import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(SpectraProperties.class)
@EnableScheduling
public class AppConfig {

    /** Délai d'établissement de connexion TCP (ms) — borne le « fail fast » quand un service est indisponible. */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /**
     * Plafond de mise en tampon des réponses (octets), appliqué à tous les WebClient LLM.
     *
     * <p>Le défaut Spring est de 256 Ko, et il ne suffit pas : un lot d'embeddings de 32
     * vecteurs de 768 dimensions sérialisés en JSON le dépasse. Le client d'embedding n'ayant
     * aucun plafond explicite, l'ingestion échouait par
     * {@code DataBufferLimitException: Exceeded limit on max bytes to buffer : 262144},
     * ouvrait le circuit breaker et n'indexait aucun chunk — alors même que
     * {@code spectra.pipeline.embedding-batch-size} vaut 32 par défaut. Autrement dit, le
     * défaut applicatif ne pouvait pas fonctionner ; seules les valeurs plus basses imposées
     * par Compose et {@code .env} masquaient le problème.
     *
     * <p>16 Mo et non {@code -1} (illimité) : une réponse aberrante doit échouer plutôt que
     * consommer le heap. ChromaDB conserve {@code -1}, ses réponses de requête étant
     * légitimement volumineuses et déjà bornées par la taille des lots demandés.
     */
    private static final int LLM_MAX_IN_MEMORY_BYTES = 16 * 1024 * 1024;

    /**
     * Connecteur partagé appliquant un timeout de connexion à tous les WebClient.
     * On n'impose volontairement PAS de {@code responseTimeout} global : les appels de
     * génération LLM (et le streaming) peuvent légitimement durer plusieurs minutes ;
     * la durée d'attente bloquante est déjà bornée par chaque {@code .block(timeout)}.
     */
    private static ClientHttpConnector connector() {
        return new ReactorClientHttpConnector(
                HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS));
    }

    /**
     * Connecteur avec timeout de réponse, pour les dépendances non-LLM (ChromaDB,
     * reranker, docparser) : libère le client si le service accepte la connexion
     * mais ne répond jamais. À ne PAS appliquer aux endpoints de génération LLM.
     */
    private static ClientHttpConnector connector(Duration responseTimeout) {
        return new ReactorClientHttpConnector(
                HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                        .responseTimeout(responseTimeout));
    }

    @Bean
    public WebClient llmWebClient(SpectraProperties props) {
        String baseUrl = props.llm() != null && props.llm().baseUrl() != null
                ? props.llm().baseUrl()
                : "http://llm-server:8081";
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(connector())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(LLM_MAX_IN_MEMORY_BYTES))
                .build();
    }

    @Bean
    public WebClient chromaDbWebClient(SpectraProperties props) {
        String baseUrl = props.chromadb() != null && props.chromadb().baseUrl() != null
                ? props.chromadb().baseUrl()
                : "http://chromadb:8000";
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(connector(Duration.ofSeconds(60)))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(-1))
                .build();
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
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(connector())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(LLM_MAX_IN_MEMORY_BYTES))
                .build();
    }

    @Bean
    public WebClient llamaCppEmbeddingWebClient(SpectraProperties props) {
        SpectraProperties.EndpointProperties embedding = props.llm() != null ? props.llm().embedding() : null;
        String baseUrl = embedding != null && embedding.baseUrl() != null
                ? embedding.baseUrl()
                : "http://llama-cpp-embed:8080";
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(connector())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(LLM_MAX_IN_MEMORY_BYTES))
                .build();
    }

    @Bean
    public WebClient rerankerWebClient(SpectraProperties props) {
        String baseUrl = props.reranker() != null
                ? props.reranker().effectiveBaseUrl()
                : "http://reranker:8000";
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(connector(Duration.ofSeconds(60)))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(LLM_MAX_IN_MEMORY_BYTES))
                .build();
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
                .clientConnector(connector(Duration.ofSeconds(180)))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(bufferBytes))
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
