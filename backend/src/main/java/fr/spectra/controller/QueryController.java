package fr.spectra.controller;

import fr.spectra.dto.QueryRequest;
import fr.spectra.dto.QueryResponse;
import fr.spectra.service.FeedbackService;
import fr.spectra.service.RagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/api/query")
@Tag(name = "Query", description = "Interrogation RAG du modèle")
public class QueryController {

    private final RagService ragService;
    private final FeedbackService feedbackService;

    public QueryController(RagService ragService, FeedbackService feedbackService) {
        this.ragService = ragService;
        this.feedbackService = feedbackService;
    }

    @PostMapping
    @Operation(summary = "Interroger le modèle avec recherche contextuelle (RAG)")
    public QueryResponse query(@Valid @RequestBody QueryRequest request) {
        return ragService.query(request);
    }

    /**
     * Recherche seule : passages classés avec leurs scores, <b>sans génération</b>. Mêmes surcharges et
     * même pipeline de retrieval que {@code /api/query} (hybride, reranking, multi-query, déduplication,
     * voisins, filtre de documents) ; en quelques secondes au lieu de minutes sur CPU. Sert à comparer
     * des réglages de retrieval (reranker, seuil, MMR…) sur tout un jeu de questions.
     *
     * <p>{@code answer} est vide et {@code ragStrategy} vaut {@code RETRIEVAL}. Les étapes qui supposent
     * le modèle de chat (RAG corrective, compression, boucle agentique) ne sont pas exécutées.</p>
     */
    @PostMapping("/retrieve")
    @Operation(summary = "Recherche seule (sans génération) : passages classés et scores")
    public QueryResponse retrieve(@Valid @RequestBody QueryRequest request) {
        long start = System.currentTimeMillis();
        RagService.RagContext ctx = ragService.retrieveContext(request, request.question(), request.overrides());
        return new QueryResponse("", ctx.sources(), System.currentTimeMillis() - start,
                ctx.rerankApplied(), ctx.hybridApplied(), false, 0, null,
                false, false, false, "RETRIEVAL",
                ctx.multiQueryApplied(), false, ctx.semanticDedupApplied(), ctx.longContextApplied());
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Interroger le modèle en streaming SSE (sources → token* → done)")
    public Flux<ServerSentEvent<String>> queryStream(@Valid @RequestBody QueryRequest request) {
        return ragService.queryStream(request);
    }

    @PostMapping("/feedback")
    @Operation(summary = "Enregistre un feedback 👍/👎 sur une réponse du Playground")
    public Map<String, String> feedback(@RequestBody FeedbackRequest request) {
        feedbackService.record(request.question(), request.answer(), request.rating(),
                request.ragMeta(), request.overrides());
        return Map.of("status", "ok");
    }

    @GetMapping("/feedback/stats")
    @Operation(summary = "Agrège le feedback Playground (taux de 👎 par stratégie et par module)")
    public FeedbackService.FeedbackStats feedbackStats() {
        return feedbackService.aggregate();
    }

    /**
     * Corps de requête du feedback. {@code rating} : "UP" ou "DOWN".
     *
     * @param ragMeta   métadonnées du pipeline de la réponse notée (stratégie, drapeaux appliqués…),
     *                  optionnel — permet de corréler les 👎 avec la configuration RAG effective
     * @param overrides surcharges de modules actives pour cette réponse, optionnel
     */
    public record FeedbackRequest(String question, String answer, String rating,
                                  Map<String, Object> ragMeta, Map<String, Object> overrides) {}
}
