package fr.spectra.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.dto.QueryRequest;
import fr.spectra.dto.QueryResponse;
import fr.spectra.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class QueryControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @Mock  private RagService ragService;
    @Mock  private fr.spectra.service.FeedbackService feedbackService;
    @InjectMocks private QueryController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ── /api/query (synchronous) ───────────────────────────────────────────────

    @Test
    void query_validRequest_returns200WithAnswer() throws Exception {
        QueryResponse response = new QueryResponse(
                "Réponse LLM", List.of(), 42L,
                false, false, false, 0, null,
                false, false, false, "DIRECT",
                false, false, false, false);
        when(ragService.query(any(QueryRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"Qu'est-ce que le RAG?","useRag":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Réponse LLM"))
                .andExpect(jsonPath("$.sources").isArray());
    }

    // ── /api/query/retrieve (recherche seule) ──────────────────────────────────

    @Test
    void retrieve_returnsRankedSourcesWithoutGeneration() throws Exception {
        var sources = List.of(
                new QueryResponse.Source("Le seuil est de 30 minutes.", "dsid_a.txt", 0.12, 0.93f, 4.2f),
                new QueryResponse.Source("Autre passage.", "dsid_b.txt", 0.4, 0.31f, null));
        var ctx = new RagService.RagContext(List.of("a", "b"), List.of(), List.of(), null, null, sources,
                "ctx", true, true, false, true, false);
        when(ragService.retrieveContext(any(QueryRequest.class), org.mockito.ArgumentMatchers.eq("Quel seuil ?"),
                any(fr.spectra.dto.RagOverrides.class))).thenReturn(ctx);

        mockMvc.perform(post("/api/query/retrieve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"Quel seuil ?","overrides":{"hybrid":true,"rerankMinScore":0.2}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(""))
                .andExpect(jsonPath("$.ragStrategy").value("RETRIEVAL"))
                .andExpect(jsonPath("$.sources.length()").value(2))
                .andExpect(jsonPath("$.sources[0].sourceFile").value("dsid_a.txt"))
                .andExpect(jsonPath("$.sources[0].rerankScore").value(0.93))
                .andExpect(jsonPath("$.rerankApplied").value(true))
                .andExpect(jsonPath("$.hybridSearchApplied").value(true))
                .andExpect(jsonPath("$.semanticDedupApplied").value(true));
        org.mockito.Mockito.verifyNoInteractions(feedbackService);
    }

    @Test
    void retrieve_blankQuestion_returns400() throws Exception {
        mockMvc.perform(post("/api/query/retrieve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"  "}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void query_blankQuestion_returns400() throws Exception {
        mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"  "}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void query_missingQuestion_returns400() throws Exception {
        mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ── /api/query/feedback ────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void feedback_forwardsRatingAndPipelineMetaToService() throws Exception {
        mockMvc.perform(post("/api/query/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"Q ?","answer":"A.","rating":"DOWN",
                                 "ragMeta":{"ragStrategy":"STANDARD","correctiveApplied":true},
                                 "overrides":{"rerank":false}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> ragMetaCap =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> overridesCap =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        org.mockito.Mockito.verify(feedbackService).record(
                org.mockito.ArgumentMatchers.eq("Q ?"),
                org.mockito.ArgumentMatchers.eq("A."),
                org.mockito.ArgumentMatchers.eq("DOWN"),
                ragMetaCap.capture(), overridesCap.capture());
        org.assertj.core.api.Assertions.assertThat(ragMetaCap.getValue())
                .containsEntry("ragStrategy", "STANDARD")
                .containsEntry("correctiveApplied", true);
        org.assertj.core.api.Assertions.assertThat(overridesCap.getValue())
                .containsEntry("rerank", false);
    }

    @Test
    void feedback_withoutPipelineMeta_stillForwardsRating() throws Exception {
        mockMvc.perform(post("/api/query/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"Q ?","answer":"A.","rating":"UP"}
                                """))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(feedbackService).record(
                org.mockito.ArgumentMatchers.eq("Q ?"),
                org.mockito.ArgumentMatchers.eq("A."),
                org.mockito.ArgumentMatchers.eq("UP"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
    }

    // ── /api/query/stream (SSE) ────────────────────────────────────────────────

    @Test
    void queryStream_validRequest_startsAsyncAndProducesEventStream() throws Exception {
        ServerSentEvent<String> sources = ServerSentEvent.<String>builder()
                .event("sources").data("[]").build();
        ServerSentEvent<String> token = ServerSentEvent.<String>builder()
                .event("token").data("Bonjour").build();
        ServerSentEvent<String> done = ServerSentEvent.<String>builder()
                .event("done").data("{\"ragStrategy\":\"DIRECT\"}").build();

        when(ragService.queryStream(any(QueryRequest.class)))
                .thenReturn(Flux.just(sources, token, done));

        MvcResult result = mockMvc.perform(post("/api/query/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"test streaming","useRag":false}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    @Test
    void queryStream_blankQuestion_returns400() throws Exception {
        mockMvc.perform(post("/api/query/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":""}
                                """))
                .andExpect(status().isBadRequest());
    }
}
