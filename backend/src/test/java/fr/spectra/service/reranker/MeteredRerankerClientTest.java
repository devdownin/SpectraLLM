package fr.spectra.service.reranker;

import fr.spectra.dto.ServiceStatus;
import fr.spectra.service.RerankerClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le décorateur doit mesurer <b>sans rien changer</b> au comportement observable.
 *
 * <p>Deux propriétés comptent davantage que les compteurs eux-mêmes, et sont testées en premier :
 * l'exception doit traverser (le repli de {@code RagService} en dépend) et {@code isAvailable()}
 * doit être délégué (le garde-fou qui rend l'activation par défaut du reranking sûre en dépend).
 * Un décorateur qui les manquerait produirait de belles métriques sur un système cassé.
 */
class MeteredRerankerClientTest {

    private MeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    private static RerankerClient ranking(List<RerankerClient.RankedResult> results) {
        return new RerankerClient() {
            @Override public ServiceStatus checkHealth() {
                return new ServiceStatus("reranker", "onnx:/models", true, "ok", 0, Map.of());
            }
            @Override public List<RankedResult> rerank(String q, List<String> docs, int topN) {
                return results;
            }
        };
    }

    private static RerankerClient failing(RuntimeException cause) {
        return new RerankerClient() {
            @Override public ServiceStatus checkHealth() {
                return new ServiceStatus("reranker", "onnx:/models", false, "unavailable", 0, Map.of());
            }
            @Override public List<RankedResult> rerank(String q, List<String> docs, int topN) {
                throw cause;
            }
        };
    }

    private MeteredRerankerClient metered(RerankerClient delegate) {
        return new MeteredRerankerClient(delegate, registry, "onnx");
    }

    private double requests(String outcome) {
        return registry.counter(MeteredRerankerClient.REQUESTS, "engine", "onnx", "outcome", outcome).count();
    }

    @Test
    @DisplayName("l'exception du moteur traverse le décorateur")
    void propagatesFailure() {
        // Critique : RagService compte sur l'exception pour retomber sur l'ordre vectoriel avec
        // rerankApplied=false. L'avaler produirait un classement identité silencieux.
        IllegalStateException cause = new IllegalStateException("modèle non chargé");

        assertThatThrownBy(() -> metered(failing(cause)).rerank("q", List.of("a"), 1))
                .isSameAs(cause);
    }

    @Test
    @DisplayName("un échec est compté comme tel, et n'est pas compté comme un succès")
    void countsFailure() {
        try {
            metered(failing(new IllegalStateException("boom"))).rerank("q", List.of("a", "b"), 1);
        } catch (IllegalStateException expected) {
            // attendu — la propagation est vérifiée par le test précédent
        }

        assertThat(requests("failure")).isEqualTo(1.0);
        assertThat(requests("success")).isZero();
    }

    @Test
    @DisplayName("un succès est compté, chronométré, et renvoie le classement inchangé")
    void countsSuccessAndPreservesResult() {
        List<RerankerClient.RankedResult> ranked =
                List.of(new RerankerClient.RankedResult(1, 0.9f), new RerankerClient.RankedResult(0, 0.3f));

        List<RerankerClient.RankedResult> got = metered(ranking(ranked)).rerank("q", List.of("a", "b"), 2);

        assertThat(got).isEqualTo(ranked);
        assertThat(requests("success")).isEqualTo(1.0);
        assertThat(registry.find(MeteredRerankerClient.DURATION)
                .tag("engine", "onnx").tag("outcome", "success").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("la taille du lot soumis est enregistrée")
    void recordsBatchSize() {
        metered(ranking(List.of())).rerank("q", List.of("a", "b", "c"), 2);

        assertThat(registry.find(MeteredRerankerClient.DOCUMENTS)
                .tag("engine", "onnx").summary().totalAmount()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("la taille du lot est enregistrée même quand l'appel échoue")
    void recordsBatchSizeOnFailure() {
        // Sinon la latence des échecs serait ininterprétable : on ne saurait pas sur quel volume.
        try {
            metered(failing(new IllegalStateException("boom"))).rerank("q", List.of("a", "b"), 1);
        } catch (IllegalStateException expected) {
            // attendu
        }

        assertThat(registry.find(MeteredRerankerClient.DOCUMENTS)
                .tag("engine", "onnx").summary().totalAmount()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("isAvailable() est délégué, jamais supposé")
    void delegatesAvailability() {
        // Le décorateur hérite sinon du défaut « true » de l'interface, ce qui ferait sur-extraire
        // des candidats pour un moteur non chargé — la régression que l'option (b) devait éviter.
        RerankerClient unavailable = new RerankerClient() {
            @Override public ServiceStatus checkHealth() {
                return new ServiceStatus("reranker", "onnx:/models", false, "unavailable", 0, Map.of());
            }
            @Override public List<RankedResult> rerank(String q, List<String> d, int n) {
                return List.of();
            }
            @Override public boolean isAvailable() { return false; }
        };

        assertThat(metered(unavailable).isAvailable()).isFalse();
        assertThat(metered(ranking(List.of())).isAvailable()).isTrue();
    }

    @Test
    @DisplayName("checkHealth() est délégué tel quel")
    void delegatesHealth() {
        ServiceStatus status = metered(ranking(List.of())).checkHealth();

        assertThat(status.available()).isTrue();
        assertThat(status.url()).isEqualTo("onnx:/models");
    }

    @Test
    @DisplayName("un lot vide reste un appel mesuré, pas un trou dans les compteurs")
    void emptyBatchIsStillCounted() {
        metered(ranking(List.of())).rerank("q", List.of(), 5);

        assertThat(requests("success")).isEqualTo(1.0);
        assertThat(registry.find(MeteredRerankerClient.DOCUMENTS)
                .tag("engine", "onnx").summary().count()).isEqualTo(1L);
    }
}
