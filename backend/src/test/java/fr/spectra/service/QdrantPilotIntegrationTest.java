package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.persistence.StreamSourceEntity;
import fr.spectra.persistence.StreamSourceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "SPECTRA_TEST_CHROMA_URL", matches = ".+")
class QdrantPilotIntegrationTest {
    private static final String COLLECTIONS = "/api/v2/tenants/default_tenant/databases/default_database/collections";
    private static final String STREAM = "source://reports";
    private static final List<Float> QUERY = List.of(1f, 0f);

    @Test
    void importAndQueryBothEnginesWithTheSameVisibilityAndDocumentIdentity() throws Exception {
        String chromaUrl = System.getenv("SPECTRA_TEST_CHROMA_URL");
        String qdrantUrl = System.getenv("SPECTRA_TEST_QDRANT_URL");
        assertThat(qdrantUrl).isNotBlank();
        WebClient chromaHttp = WebClient.builder().baseUrl(chromaUrl).build();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String name = "test_" + suffix;
        String alias = "spectra_pilot_" + suffix;
        String collectionId = create(chromaHttp, name);
        String otherId = create(chromaHttp, "other_" + suffix);
        seed(chromaHttp, collectionId,
                List.of("homonym", "obsolete", "permitted", "published", "legacy"),
                List.of(List.of(1, 0), List.of(0.99, 0.1), List.of(0.9, 0.4), List.of(0.7, 0.7), List.of(0.6, 0.8)),
                List.of(Map.of("sha256", "wrong", "sourceFile", "report.pdf"),
                        Map.of("sha256", "old", "sourceFile", STREAM),
                        Map.of("sha256", "allowed", "sourceFile", "report.pdf"),
                        Map.of("sha256", "current", "sourceFile", STREAM),
                        Map.of("sourceFile", "report.pdf")));
        seed(chromaHttp, otherId, List.of("other"), List.of(List.of(1, 0)),
                List.of(Map.of("sourceFile", "other.pdf")));

        Process copy = new ProcessBuilder("python3", "../scripts/import_qdrant_pilot.py",
                "--chroma", chromaUrl, "--qdrant", qdrantUrl, "--source", name, "--target", alias, "--batch", "2")
                .inheritIO().start();
        try {
            assertThat(copy.waitFor(60, TimeUnit.SECONDS)).as("snapshot import completed").isTrue();
            assertThat(copy.exitValue()).isZero();
        } finally {
            if (copy.isAlive()) copy.destroyForcibly();
        }

        var props = mock(SpectraProperties.class);
        when(props.chromadb()).thenReturn(new SpectraProperties.ChromaDbProperties(chromaUrl, name));
        var repository = mock(StreamSourceRepository.class);
        var publication = new StreamSourceEntity(STREAM, name);
        publication.setContentHash("current");
        publication.setVersionedPublication(true);
        when(repository.findById(STREAM)).thenReturn(Optional.of(publication));
        var visibility = new StreamVersionVisibility(repository);
        var chroma = new ChromaDbClient(chromaHttp, props, mock(ModelRegistryService.class));
        chroma.setStreamVersionVisibility(visibility);
        var filter = new RetrievalFilterResolver.ResolvedFilter(
                Set.of("allowed", "old", "current"), Set.of("report.pdf", STREAM));

        var meters = new SimpleMeterRegistry();
        chroma.setRetrievalMeters(meters);
        assertThat(ids(chroma, collectionId, filter)).containsExactly("permitted", "published", "legacy");
        var builder = WebClient.builder();
        String key = System.getenv().getOrDefault("SPECTRA_QDRANT_API_KEY", "");
        chroma.setQdrantRetrievalPilot(new QdrantRetrievalPilot(builder, visibility, qdrantUrl, key, collectionId, alias));
        assertThat(ids(chroma, collectionId, filter)).containsExactly("permitted", "published", "legacy");
        assertThat(ids(chroma, otherId, RetrievalFilterResolver.ResolvedFilter.NONE)).containsExactly("other");
        assertThat(meters.get("spectra.retrieval.dense").tags("engine", "chroma", "outcome", "success")
                .counter().count()).isEqualTo(2);
        assertThat(meters.get("spectra.retrieval.dense").tags("engine", "qdrant", "outcome", "success")
                .counter().count()).isEqualTo(1);

        chroma.setQdrantRetrievalPilot(new QdrantRetrievalPilot(
                builder, visibility, qdrantUrl, key, collectionId, alias + "_missing"));
        var fts = mock(FtsService.class);
        when(fts.search("report", name, 20)).thenReturn(List.of(
                new BM25Index.ScoredDoc("permitted", "permitted", "report.pdf", 2)));
        var hybrid = new HybridSearchService(chroma, fts, props);
        hybrid.setRetrievalMeters(meters);
        assertThat(hybrid.search("report", QUERY, collectionId, name, 3))
                .extracting(HybridSearchService.HybridChunk::id).containsExactly("permitted");
        assertThat(meters.get("spectra.retrieval.dense").tags("engine", "qdrant", "outcome", "error")
                .counter().count()).isEqualTo(1);
        assertThat(meters.get("spectra.retrieval.bm25.only").tag("reason", "dense_error")
                .counter().count()).isEqualTo(1);
    }

    private static List<String> ids(ChromaDbClient client, String id, RetrievalFilterResolver.ResolvedFilter filter) {
        return ChromaQueryResult.from(FilteredVectorSearch.query(client, id, QUERY, 3, filter)).ids();
    }

    private static String create(WebClient http, String name) {
        return (String) http.post().uri(COLLECTIONS)
                .bodyValue(Map.of("name", name, "configuration", Map.of("hnsw", Map.of("space", "cosine"))))
                .retrieve().bodyToMono(Map.class).block(Duration.ofSeconds(15)).get("id");
    }

    private static void seed(WebClient http, String id, List<String> ids, List<?> embeddings,
                             List<Map<String, String>> metadata) {
        http.post().uri(COLLECTIONS + "/{id}/add", id)
                .bodyValue(Map.of("ids", ids, "documents", ids, "embeddings", embeddings, "metadatas", metadata))
                .retrieve().toBodilessEntity().block(Duration.ofSeconds(15));
    }
}
