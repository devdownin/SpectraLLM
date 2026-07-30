package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.ServiceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the Python Cross-Encoder reranker service.
 * Active only when {@code spectra.reranker.enabled=true}.
 */
@Service
@ConditionalOnProperty(prefix = "spectra.reranker", name = "enabled", havingValue = "true")
public class CrossEncoderRerankerClient implements RerankerClient {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderRerankerClient.class);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final Duration timeout;
    private final String baseUrl;

    public CrossEncoderRerankerClient(@Qualifier("rerankerWebClient") WebClient webClient,
                                      SpectraProperties props) {
        this.webClient = webClient;
        int seconds = props.reranker() != null
                ? props.reranker().effectiveTimeoutSeconds()
                : 30;
        this.timeout = Duration.ofSeconds(seconds);
        this.baseUrl = props.reranker() != null
                ? props.reranker().effectiveBaseUrl()
                : "http://reranker:8000";
    }

    @Override
    public ServiceStatus checkHealth() {
        long start = System.currentTimeMillis();
        try {
            webClient.get()
                    .uri("/health")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(HEALTH_TIMEOUT);
            long elapsed = System.currentTimeMillis() - start;
            return new ServiceStatus("reranker", baseUrl, true, "ok", elapsed, Map.of());
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("Reranker indisponible: {}", e.getMessage());
            return ServiceStatus.unavailable("reranker", baseUrl, elapsed);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RankedResult> rerank(String query, List<String> documents, int topN) {
        // Vivier vide : rien à classer. Le service Python répond « results: [] » dans ce cas,
        // mais le contrôle d'anomalie plus bas transformait cette réponse légitime en
        // IllegalStateException — avec un message absurde (« sans résultats pour 0 document »).
        // Le seul appelant (RagService) garde déjà l'appel derrière !allChunks.isEmpty(), donc
        // ce n'était pas atteignable ; on l'aligne néanmoins sur le service, car une
        // implémentation exécutée dans la JVM renverrait naturellement une liste vide et ne doit
        // pas avoir à reproduire ce défaut. Économise en outre un aller-retour HTTP inutile.
        if (documents.isEmpty()) {
            return List.of();
        }

        Map<String, Object> body = Map.of(
                "query", query,
                "documents", documents,
                "top_n", topN
        );

        Map<String, Object> response = webClient.post()
                .uri("/rerank")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(timeout);

        // Réponse anormale (nulle ou vide alors que des documents ont été soumis) : on lève
        // une exception plutôt que de renvoyer un classement « identité » avec scores 0 —
        // RagService retombe alors sur l'ordre vectoriel avec rerankApplied=false, au lieu
        // d'exposer des métadonnées trompeuses (rerankApplied=true, rerankScores=0) qui
        // faussaient benchmarks et ablations.
        if (response == null) {
            throw new IllegalStateException("Réponse nulle du service reranker");
        }

        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        if (results == null || results.isEmpty()) {
            throw new IllegalStateException(
                    "Réponse reranker sans résultats pour " + documents.size() + " document(s)");
        }

        return results.stream()
                .map(r -> new RankedResult(
                        ((Number) r.get("index")).intValue(),
                        ((Number) r.get("score")).floatValue()
                ))
                .toList();
    }
}
