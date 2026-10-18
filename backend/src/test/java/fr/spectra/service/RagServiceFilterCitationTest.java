package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.CitationReport;
import fr.spectra.dto.QueryRequest;
import fr.spectra.dto.QueryResponse;
import fr.spectra.dto.RagOverrides;
import fr.spectra.dto.RetrievalFilter;
import fr.spectra.persistence.IngestedFileEntity;
import fr.spectra.persistence.IngestedFileRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Filtre de recherche (catégories GED, sources), surcharges de calibration (seuil du reranker) et
 * vérification des citations, de bout en bout dans {@link RagService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagServiceFilterCitationTest {

    @Mock private ChromaDbClient chromaDbClient;
    @Mock private EmbeddingService embeddingService;
    @Mock private LlmChatClient llmChatClient;
    @Mock private RerankerClient reranker;
    @Mock private IngestedFileRepository files;
    @Mock private SpectraProperties props;
    @Mock private SpectraProperties.PipelineProperties pipeline;

    @BeforeEach
    void setUp() {
        when(props.pipeline()).thenReturn(pipeline);
        when(pipeline.generationTimeoutSeconds()).thenReturn(30);
        when(props.chromadb()).thenReturn(null);
        when(props.rag()).thenReturn(new SpectraProperties.RagProperties(null, null, null, null));
        when(chromaDbClient.getOrCreateCollection(anyString())).thenReturn("col-1");
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));
        when(chromaDbClient.query(eq("col-1"), anyList(), anyInt(), any())).thenReturn(vectorResult());
        when(chromaDbClient.query(eq("col-1"), anyList(), anyInt())).thenReturn(vectorResult());
        when(llmChatClient.chat(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn("La garantie dure deux ans [1]. Le prix est de 10 € [2].");
        when(reranker.isAvailable()).thenReturn(true);
    }

    private static Map<String, Object> vectorResult() {
        return Map.of(
                "documents", List.of(List.of("La garantie dure deux ans.", "Le stock est en entrepôt.")),
                "metadatas", List.of(List.of(Map.of("sourceFile", "a.pdf"), Map.of("sourceFile", "b.pdf"))),
                "distances", List.of(List.of(0.1, 0.2)));
    }

    private RagService ragService(Optional<RerankerClient> rr, Optional<CitationVerifier> verifier) {
        return new RagService(
                chromaDbClient, embeddingService, llmChatClient,
                rr, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                profiles(), props, new ObjectMapper(), new SimpleMeterRegistry(),
                verifier, Optional.of(new RetrievalFilterResolver(files)));
    }

    private static ActiveModelProfileService profiles() {
        ActiveModelProfileService profiles = mock(ActiveModelProfileService.class);
        when(profiles.current()).thenReturn(ActiveModelProfileService.ModelProfile.DEFAULT);
        return profiles;
    }

    private static QueryRequest request(RetrievalFilter filter, RagOverrides overrides) {
        return new QueryRequest("Garantie ?", 2, 2, null, null, null, null, true, overrides, filter);
    }

    @Test
    void filtreParSource_transmisAChromaEnWhere() {
        ragService(Optional.empty(), Optional.empty())
                .retrieveContext(request(new RetrievalFilter(null, List.of("a.pdf"), null, null), null));

        verify(chromaDbClient).query(eq("col-1"), anyList(), eq(2),
                eq(Map.of("sourceFile", Map.of("$in", List.of("a.pdf")))));
        verify(chromaDbClient, never()).query(eq("col-1"), anyList(), anyInt());
    }

    @Test
    void filtreParCategorie_resoluEnEmpreintes() {
        IngestedFileEntity doc = new IngestedFileEntity("sha-a", "a.pdf", "pdf", Instant.now(), 2, null, null);
        doc.setCategories(List.of("Juridique"));
        when(files.findAllByOrderByIngestedAtDesc()).thenReturn(List.of(doc));

        ragService(Optional.empty(), Optional.empty())
                .retrieveContext(request(new RetrievalFilter(List.of("juridique"), null, null, null), null));

        verify(chromaDbClient).query(eq("col-1"), anyList(), eq(2), eq(Map.of("$or", List.of(
                Map.of("sha256", Map.of("$in", List.of("sha-a"))),
                Map.of("sourceFile", Map.of("$in", List.of("a.pdf")))))));
    }

    @Test
    void filtreSansDocument_aucuneRecherche() {
        when(files.findAllByOrderByIngestedAtDesc()).thenReturn(List.of());

        RagService.RagContext ctx = ragService(Optional.empty(), Optional.empty())
                .retrieveContext(request(new RetrievalFilter(List.of("rh"), null, null, null), null));

        assertThat(ctx.contextChunks()).isEmpty();
        verify(chromaDbClient, never()).query(anyString(), anyList(), anyInt(), any());
        verify(chromaDbClient, never()).query(anyString(), anyList(), anyInt());
    }

    @Test
    void filtreParCategorieSansGed_refuseAuLieuDElargir() {
        RagService rag = new RagService(
                chromaDbClient, embeddingService, llmChatClient,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                profiles(), props, new ObjectMapper(), new SimpleMeterRegistry());

        assertThatThrownBy(() -> rag.retrieveContext(
                request(new RetrievalFilter(List.of("rh"), null, null, null), null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void surchargeDeCalibration_seuilDuRerankerPrimeSurLaConfiguration() {
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(
                new RerankerClient.RankedResult(0, 0.8f), new RerankerClient.RankedResult(1, 0.1f)));

        RagService.RagContext ctx = ragService(Optional.of(reranker), Optional.empty())
                .retrieveContext(request(null, null), "Garantie ?", RagOverrides.NONE.withTuning(0.5f, null, "none"));

        assertThat(ctx.contextChunks()).containsExactly("La garantie dure deux ans.");
    }

    private CitationVerifier verifier(boolean strict) {
        when(props.rag()).thenReturn(new SpectraProperties.RagProperties(null, null, null, null, true, 0.5f, strict));
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            List<String> docs = inv.getArgument(1);
            String claim = inv.getArgument(0);
            boolean match = claim.contains("garantie") && docs.getFirst().contains("garantie");
            return List.of(new RerankerClient.RankedResult(0, match ? 0.9f : 0.1f));
        });
        return new CitationVerifier(Optional.of(reranker), props, new SimpleMeterRegistry());
    }

    @Test
    void query_rapportDeCitationsJoint_reponseIntacteHorsStrict() {
        QueryResponse resp = ragService(Optional.empty(), Optional.of(verifier(false)))
                .query(request(null, RagOverrides.NONE.withTuning(null, null, "none")));

        CitationReport report = resp.citations();
        assertThat(report).isNotNull();
        assertThat(report.supportedSentences()).isEqualTo(1);
        assertThat(report.unsupportedSentences()).isEqualTo(1);
        assertThat(resp.answer()).contains("10 €");
    }

    @Test
    void query_strict_retireLesPhrasesNonEtayees() {
        QueryResponse resp = ragService(Optional.empty(), Optional.of(verifier(true)))
                .query(request(null, RagOverrides.NONE.withTuning(null, null, "none")));

        assertThat(resp.answer()).isEqualTo("La garantie dure deux ans [1].");
        assertThat(resp.citations().faithfulness()).isEqualTo(0.5);
    }
}
