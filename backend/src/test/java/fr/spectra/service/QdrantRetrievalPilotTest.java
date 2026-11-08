package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QdrantRetrievalPilotTest {
    @Test
    void pilotPreservesOriginalIdsAndRefillsAfterRejectingAnUnpublishedRevision() throws Exception {
        String sourceId = "93ff75f1-698c-44aa-b137-879a364046e0";
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String obsolete = """
                    {"id":1,"score":1,"payload":{"sourceCollectionId":"%s","chunkId":"old","document":"obsolete",
                     "metadata":{"sha256":"obsolete","sourceFile":"source://reports"}}}
                    """.formatted(sourceId);
            String published = """
                    {"id":2,"score":0.75,"payload":{"sourceCollectionId":"%s","chunkId":"chunk-published","document":"published",
                     "metadata":{"sha256":"published","sourceFile":"source://reports"}}}
                    """.formatted(sourceId);
            respond(server, obsolete);
            respond(server, obsolete + "," + published);
            StreamVersionVisibility visibility = mock(StreamVersionVisibility.class);
            when(visibility.snapshot()).thenReturn((hash, source) -> "published".equals(hash));
            WebClient.Builder builder = WebClient.builder();
            SpectraProperties properties = mock(SpectraProperties.class);
            ChromaDbClient chroma = new ChromaDbClient(builder.build(), properties, mock(ModelRegistryService.class));
            QdrantRetrievalPilot pilot = new QdrantRetrievalPilot(builder, visibility, server.url("/").toString(),
                    "pilot-key", sourceId, "spectra_pilot_reports");
            chroma.setQdrantRetrievalPilot(pilot);

            var filter = new RetrievalFilterResolver.ResolvedFilter(
                    Set.of("obsolete", "published"), Set.of("source://reports"));
            var result = ChromaQueryResult.from(
                    FilteredVectorSearch.query(chroma, sourceId, List.of(1f, 0f), 1, filter));

            assertThat(result.ids()).containsExactly("chunk-published");
            assertThat(result.documents()).containsExactly("published");
            assertThat(result.distances()).containsExactly(0.25);
            var request = server.takeRequest(3, TimeUnit.SECONDS);
            assertThat(request.getPath()).isEqualTo("/collections/spectra_pilot_reports/points/query");
            assertThat(request.getHeader("api-key")).isEqualTo("pilot-key");
            var body = new ObjectMapper().readTree(request.getBody().readUtf8());
            assertThat(body.at("/filter/should/1/must/1/is_empty/key").asText()).isEqualTo("metadata.sha256");
            var refill = server.takeRequest(3, TimeUnit.SECONDS);
            assertThat(new ObjectMapper().readTree(refill.getBody().readUtf8()).path("limit").asInt()).isEqualTo(2);
            assertThat(pilot.serves("another-collection")).isFalse();

            respond(server, published.replace(sourceId, "another-collection"));
            assertThatThrownBy(() -> FilteredVectorSearch.query(chroma, sourceId, List.of(1f, 0f), 1, filter))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("different Chroma collection");
        }
    }

    private static void respond(MockWebServer server, String points) {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"ok\",\"result\":{\"points\":[" + points + "]}}"));
    }
}
