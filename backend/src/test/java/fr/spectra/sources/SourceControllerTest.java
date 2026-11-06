package fr.spectra.sources;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.ApiKeyFilter;
import fr.spectra.controller.GlobalExceptionHandler;
import fr.spectra.service.IngestionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP validation/authentication and persisted connector lifecycle through the real service. */
@SpringJUnitConfig(SourceJpaLifecycleTest.JpaConfig.class)
class SourceControllerTest {
    @Autowired SourceInstanceRepository sources;
    @Autowired SourceDocumentRepository documents;
    @Autowired SourceRunRepository runs;
    private SourceSyncService service;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();
    @BeforeEach void setup() throws Exception {
        var ingestion = mock(IngestionService.class);
        when(ingestion.upsertFromStream(anyString(), anyString(), any(), anyString(), isNull(), anyMap()))
                .thenAnswer(a -> new IngestionService.UpsertResult(a.getArgument(0), IngestionService.UpsertResult.Kind.UPSERTED, 0, 1));
        SourceConnector connector = new SourceConnector() {
            public SourceModels.Type type() { return SourceModels.Type.LOCAL_FOLDER; }
            public void validate(SourceModels.Config c) { if (c.rootPath() == null || c.rootPath().isBlank()) throw new IllegalArgumentException("Folder required"); }
            public SourceModels.ConnectionTest test(SourceModels.Config c) { return new SourceModels.ConnectionTest(true, "Readable folder", 1); }
            public boolean scan(SourceModels.Config c, Sink sink) throws Exception {
                sink.accept(new Item("guide.md", "Guide", null, "guide.md", "revision", "Searchable content".getBytes(StandardCharsets.UTF_8), Map.of()));
                return true;
            }
        };
        service = new SourceSyncService(sources, documents, runs, ingestion, List.of(connector), 1);
        mvc = MockMvcBuilders.standaloneSetup(new SourceController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new ApiKeyFilter("test-key")).build();
    }
    @AfterEach void stop() { service.stop(); }
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) { return request.header("X-API-Key", "test-key"); }
    private String request(String name, boolean enabled, String collection) {
        return "{\"name\":\"" + name + "\",\"type\":\"LOCAL_FOLDER\",\"collection\":\"" + collection
                + "\",\"enabled\":" + enabled + ",\"intervalMinutes\":0,\"config\":{\"rootPath\":\"/fixture\",\"extensions\":[\"md\"],\"maxDocuments\":10}}";
    }
    private String create(String name) throws Exception {
        String response = mvc.perform(auth(post("/api/sources")).contentType(MediaType.APPLICATION_JSON).content(request(name, true, "spectra_documents")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value(name)).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asText();
    }
    @Test void authenticatedHttpLifecyclePublishesListsPausesAndDeletesOnlyItsSource() throws Exception {
        mvc.perform(get("/api/sources")).andExpect(status().isUnauthorized());
        mvc.perform(auth(get("/api/sources/catalog"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        mvc.perform(auth(post("/api/sources/test")).contentType(MediaType.APPLICATION_JSON).content(request("Preview", true, "spectra_documents")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        String id = create("HTTP lifecycle");
        mvc.perform(auth(get("/api/sources"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(id)));
        mvc.perform(auth(post("/api/sources/{id}/test", id))).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(auth(put("/api/sources/{id}", id)).contentType(MediaType.APPLICATION_JSON).content(request("Paused HTTP source", false, "spectra_documents")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(auth(post("/api/sources/{id}/sync", id))).andExpect(status().isConflict());
        mvc.perform(auth(put("/api/sources/{id}", id)).contentType(MediaType.APPLICATION_JSON).content(request("Resumed HTTP source", true, "spectra_documents"))).andExpect(status().isOk());
        mvc.perform(auth(post("/api/sources/{id}/sync", id))).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("RUNNING"));
        await().atMost(Duration.ofSeconds(5)).until(() -> service.get(id).status() == SourceModels.Status.SUCCESS);
        mvc.perform(auth(get("/api/sources/{id}", id))).andExpect(status().isOk()).andExpect(jsonPath("$.documentCount").value(1));
        mvc.perform(auth(get("/api/sources/{id}/runs", id))).andExpect(status().isOk()).andExpect(jsonPath("$[0].indexed").value(1));
        mvc.perform(auth(get("/api/sources/{id}/documents", id)).param("page", "-1").param("size", "1000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100)).andExpect(jsonPath("$.number").value(0));
        String doc = documents.findBySourceId(id).getFirst().id;
        mvc.perform(auth(get("/api/sources/{id}/documents/{doc}", id, doc))).andExpect(status().isOk()).andExpect(jsonPath("$.externalId").value("guide.md"));
        String other = create("Other source");
        mvc.perform(auth(get("/api/sources/{id}/documents/{doc}", other, doc))).andExpect(status().isNotFound());
        mvc.perform(auth(post("/api/sources/{id}/cancel", id))).andExpect(status().isNoContent());
        mvc.perform(auth(delete("/api/sources/{id}", id))).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/sources/{id}", id))).andExpect(status().isNotFound());
        assertThat(sources.findById(other)).isPresent();
        service.delete(other);
    }
    @Test void invalidBoundsAndIdentityMutationAreRejectedWithoutChangingPersistedSource() throws Exception {
        long count = sources.count();
        String invalid = request("Invalid", true, "spectra_documents").replace("\"maxDocuments\":10", "\"maxDocuments\":10001");
        mvc.perform(auth(post("/api/sources")).contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        assertThat(sources.count()).isEqualTo(count);
        String id = create("Stable collection");
        mvc.perform(auth(put("/api/sources/{id}", id)).contentType(MediaType.APPLICATION_JSON).content(request("Attempted move", true, "another_collection")))
                .andExpect(status().isBadRequest());
        assertThat(service.get(id).collection()).isEqualTo("spectra_documents");
        assertThat(service.get(id).name()).isEqualTo("Stable collection");
        service.delete(id);
    }
}
