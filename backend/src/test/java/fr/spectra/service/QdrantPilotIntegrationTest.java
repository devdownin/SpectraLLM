package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.model.TextChunk;
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

    @Test
    void nativeQdrantStoresReadsDeletesAndRebuildsAcrossDimensions() {
        String url = System.getenv("SPECTRA_TEST_QDRANT_URL");
        String name = "native_" + UUID.randomUUID().toString().replace("-", "");
        var props = mock(SpectraProperties.class);
        var registry = mock(ModelRegistryService.class);
        when(registry.getActiveEmbeddingModel()).thenReturn("nomic-embed-text");
        var facade = new ChromaDbClient(WebClient.builder().build(), props, registry);
        var metrics = new SimpleMeterRegistry();
        facade.setQdrantStore(new QdrantStore(WebClient.builder().baseUrl(url).build(), url,
                new com.fasterxml.jackson.databind.ObjectMapper(), metrics));
        assertThat(facade.checkHealth().name()).isEqualTo("qdrant");
        String id = facade.getOrCreateCollection(name);
        assertThat(ids(facade, id, RetrievalFilterResolver.ResolvedFilter.NONE)).isEmpty();

        var chunks = List.of(
                new TextChunk("doc:0", "unique ticket OPS-33", 0, "report.txt",
                        Map.of("sha256", "doc", "sourceFile", "report.txt", "chunkIndex", "0")),
                new TextChunk("doc:1", "next passage", 1, "report.txt",
                        Map.of("sha256", "doc", "sourceFile", "report.txt", "chunkIndex", "1")),
                new TextChunk("other:0", "a homonym", 0, "report.txt",
                        Map.of("sha256", "other", "sourceFile", "report.txt", "chunkIndex", "0")));
        facade.addDocuments(id, chunks, List.of(List.of(0.9f, 0.1f), List.of(0.7f, 0.7f), List.of(1f, 0f)));
        facade.addDocuments(id, chunks.subList(0, 1), List.of(List.of(0.9f, 0.1f)));
        assertThat(facade.count(id)).isEqualTo(3);
        assertThat(facade.listCollections()).anyMatch(row -> name.equals(row.get("name")));
        assertThat(ids(facade, id, new RetrievalFilterResolver.ResolvedFilter(Set.of("doc"), Set.of("report.txt"))))
                .containsExactly("doc:0", "doc:1");
        var queries = metrics.get("spectra.qdrant.http").tag("operation", "query").timer();
        long queryCalls = queries.count();
        var restricted = new RetrievalFilterResolver.ResolvedFilter(Set.of("doc"), Set.of("report.txt"));
        assertThat(ChromaQueryResult.from(FilteredVectorSearch.query(facade, id, QUERY, 1, restricted)).ids())
                .containsExactly("doc:0");
        assertThat(queries.count()).isEqualTo(queryCalls + 1);
        assertThat(facade.findChunksContaining(id, "PS-3", null, 2))
                .extracting(ChromaDbClient.ChunkHit::text).containsExactly("unique ticket OPS-33");
        assertThat(facade.getChunksByIndex(id, "sha256", "doc", List.of(1))).containsEntry(1, "next passage");
        assertThat(facade.findChunksContaining(id, "OPS-33", null, 2))
                .extracting(ChromaDbClient.ChunkHit::text).containsExactly("unique ticket OPS-33");
        assertThat(facade.findChunksContaining(id, "ops-33", null, 2)).isEmpty();
        var scroll = metrics.get("spectra.qdrant.http").tag("operation", "scroll").timer();
        long calls = scroll.count();
        assertThat(facade.findChunksContaining(id, "OPS-99999", null, 1)).isEmpty();
        assertThat(scroll.count()).isEqualTo(calls + 1);
        assertThat(metrics.get("spectra.qdrant.response.bytes").tag("operation", "scroll").summary().totalAmount())
                .isPositive();
        assertThat(metrics.get("spectra.qdrant.engine").tag("operation", "scroll").timer().count()).isPositive();
        assertThat(facade.getMetadatasByIds(id, List.of("doc:0"))).containsKey("doc:0");
        var page1 = (List<?>) facade.getDocumentsPaged(id, 2, 0).get("ids");
        var page2 = (List<?>) facade.getDocumentsPaged(id, 2, 2).get("ids");
        assertThat(page1).hasSize(2);
        assertThat(page2).hasSize(1);
        assertThat(page1.stream().noneMatch(page2::contains)).isTrue();

        assertThat(facade.deleteByMetadata(id, "sha256", "other")).isEqualTo(1);
        when(registry.getActiveEmbeddingModel()).thenReturn("replacement");
        facade.beginReindex(name);
        facade.updateEmbeddings(id, List.of("doc:0", "doc:1"), List.of(List.of(1f, 0f, 0f), List.of(0f, 1f, 0f)));
        assertThat(facade.count(id)).isEqualTo(2);
        facade.updateEmbeddingStamp(name, id, "replacement");
        facade.completeReindex(name);
        assertThat(facade.getOrCreateCollection(name)).isEqualTo(id);
        assertThat(ChromaQueryResult.from(facade.query(id, List.of(1f, 0f, 0f), 1)).ids()).containsExactly("doc:0");
        facade.deleteChunksByIds(id, List.of("doc:0"));
        assertThat(facade.count(id)).isEqualTo(1);
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
