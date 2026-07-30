package fr.spectra.service.reranker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import fr.spectra.service.CrossEncoderRerankerClient;
import fr.spectra.service.RerankerClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contrat observable du client de reranking, capturé <b>avant</b> son remplacement par une
 * implémentation exécutée dans la JVM (lot 2 de {@code docs/process/audit-python-java.fr.md}).
 *
 * <p>Le reranker n'avait aucun test : ni la forme de la requête envoyée au service, ni le
 * traitement des réponses anormales n'étaient verrouillés. Or c'est précisément ce contrat que la
 * future implémentation Java doit reproduire — et deux de ces comportements sont subtils :
 *
 * <ul>
 *   <li>une réponse <b>vide alors que des documents ont été soumis</b> doit lever une exception,
 *       jamais renvoyer un classement « identité » à scores nuls : {@code RagService} retombe
 *       alors sur l'ordre vectoriel avec {@code rerankApplied=false}, au lieu de publier des
 *       métadonnées trompeuses ({@code rerankApplied=true}, {@code rerankScores=0}) qui faussent
 *       benchmarks et ablations ;</li>
 *   <li>un vivier <b>vide</b>, en revanche, n'est pas une anomalie : le service renvoie une liste
 *       vide et le client la propage telle quelle.</li>
 * </ul>
 */
class CrossEncoderRerankerClientContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockWebServer server;
    private CrossEncoderRerankerClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        String baseUrl = server.url("/").toString();
        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.reranker()).thenReturn(new SpectraProperties.RerankerProperties(
                true, baseUrl, "cross-encoder/mmarco-mMiniLMv2-L12-H384-v1", 10, 20));
        client = new CrossEncoderRerankerClient(webClient, props);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    // ── Requête émise ─────────────────────────────────────────────────────────────────────

    @Test
    void requete_posteQueryDocumentsEtTopNSurRerank() throws Exception {
        server.enqueue(json("{\"results\":[{\"index\":1,\"score\":0.9}],\"model\":\"m\"}"));

        client.rerank("question ?", List.of("doc A", "doc B"), 1);

        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/rerank");

        JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
        assertThat(body.get("query").asText()).isEqualTo("question ?");
        assertThat(body.get("top_n").asInt()).isEqualTo(1);
        assertThat(body.get("documents")).hasSize(2);
        assertThat(body.get("documents").get(0).asText()).isEqualTo("doc A");
        assertThat(body.get("documents").get(1).asText()).isEqualTo("doc B");
    }

    @Test
    void requete_documentsTransmisDansLOrdreDeSoumission() throws Exception {
        // Les index de la réponse désignent des positions dans CE vivier : tout réordonnancement
        // côté client corromprait silencieusement l'association passage ↔ score.
        server.enqueue(json("{\"results\":[{\"index\":0,\"score\":0.5}],\"model\":\"m\"}"));

        client.rerank("q", List.of("z", "a", "m"), 1);

        JsonNode documents = MAPPER.readTree(
                server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8()).get("documents");
        assertThat(List.of(documents.get(0).asText(), documents.get(1).asText(),
                documents.get(2).asText())).containsExactly("z", "a", "m");
    }

    // ── Réponse nominale ──────────────────────────────────────────────────────────────────

    @Test
    void reponse_indexEtScoresPreservesDansLOrdreDuService() {
        // Le service renvoie déjà les résultats triés : le client ne doit pas les retrier.
        server.enqueue(json("""
                {"results":[{"index":4,"score":0.91},{"index":0,"score":0.42},
                            {"index":2,"score":-1.75}],"model":"m"}"""));

        List<RerankerClient.RankedResult> results =
                client.rerank("q", List.of("a", "b", "c", "d", "e"), 3);

        assertThat(results).extracting(RerankerClient.RankedResult::index)
                .containsExactly(4, 0, 2);
        assertThat(results).extracting(RerankerClient.RankedResult::score)
                .containsExactly(0.91f, 0.42f, -1.75f);
    }

    @Test
    void reponse_scoresNegatifsAcceptes() {
        // Un logit brut (échelle non sigmoïde) est négatif la plupart du temps : le client ne doit
        // pas supposer un score dans [0,1]. Point d'attention direct pour le portage Java.
        server.enqueue(json("{\"results\":[{\"index\":0,\"score\":-8.5}],\"model\":\"m\"}"));

        assertThat(client.rerank("q", List.of("a"), 1))
                .singleElement()
                .satisfies(r -> assertThat(r.score()).isEqualTo(-8.5f));
    }

    @Test
    void reponse_scoreEntierConvertiSansErreur() {
        // Jackson désérialise 1 en Integer et non en Double : le cast doit passer par Number.
        server.enqueue(json("{\"results\":[{\"index\":0,\"score\":1}],\"model\":\"m\"}"));

        assertThat(client.rerank("q", List.of("a"), 1))
                .singleElement()
                .satisfies(r -> assertThat(r.score()).isEqualTo(1.0f));
    }

    // ── Vivier vide : cas normal, pas une anomalie ─────────────────────────────────────────

    @Test
    void vivierVide_resultatVideSansAppelReseau() {
        // Aucune réponse mise en file : le client ne doit pas appeler le service du tout.
        assertThat(client.rerank("q", List.of(), 5)).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    // ── Réponses anormales : exception, pour que RagService retombe proprement ─────────────

    @Test
    void resultatsVidesAlorsQueDesDocumentsOntEteSoumis_exception() {
        server.enqueue(json("{\"results\":[],\"model\":\"m\"}"));

        assertThatThrownBy(() -> client.rerank("q", List.of("a", "b"), 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 document");
    }

    @Test
    void champResultatsAbsent_exception() {
        server.enqueue(json("{\"model\":\"m\"}"));

        assertThatThrownBy(() -> client.rerank("q", List.of("a"), 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void erreurServeur_exceptionPropagee() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        // L'exception (quelle qu'elle soit) doit remonter : RagService la rattrape et repasse en
        // ordre vectoriel avec rerankApplied=false.
        assertThatThrownBy(() -> client.rerank("q", List.of("a"), 1))
                .isInstanceOf(Exception.class);
    }

    @Test
    void validationDuTopN_rejetCoteService_propagee() {
        // top_n < 1 est rejeté en 422 par le service (Pydantic Field(ge=1)). Une implémentation
        // in-process devra refuser de la même façon, et non renvoyer un sous-ensemble arbitraire.
        server.enqueue(new MockResponse().setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"detail\":[{\"loc\":[\"body\",\"top_n\"],\"msg\":\"greater than or equal to 1\"}]}"));

        assertThatThrownBy(() -> client.rerank("q", List.of("a"), 0))
                .isInstanceOf(Exception.class);
    }

    // ── Santé ─────────────────────────────────────────────────────────────────────────────

    @Test
    void sante_serviceJoignable() {
        server.enqueue(json("{\"status\":\"ok\",\"model\":\"cross-encoder/mmarco-mMiniLMv2-L12-H384-v1\"}"));

        fr.spectra.dto.ServiceStatus status = client.checkHealth();

        assertThat(status.available()).isTrue();
        assertThat(status.name()).isEqualTo("reranker");
    }

    @Test
    void sante_serviceEnErreur_rapporteIndisponible() {
        server.enqueue(new MockResponse().setResponseCode(503));

        fr.spectra.dto.ServiceStatus status = client.checkHealth();

        assertThat(status.available()).isFalse();
        assertThat(status.name()).isEqualTo("reranker");
    }
}
