package fr.spectra.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.QueryRequest;
import fr.spectra.dto.QueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service RAG :
 * 1. Embedding de la question
 * 2. Recherche vectorielle ChromaDB (ou hybride vector+BM25 si activé)
 * 3. Re-ranking Cross-Encoder optionnel (si activé)
 * 4. Génération via le serveur LLM
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final String COLLECTION_NAME = "spectra_documents";

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            Tu es un assistant spécialisé dans l'exploitation autoroutière française.
            Réponds de manière précise et concise en te basant UNIQUEMENT sur le contexte fourni ci-dessous.
            Si le contexte ne contient pas l'information demandée, dis-le clairement.
            Ne fabrique pas d'information.

            === CONTEXTE ===
            %s
            === FIN DU CONTEXTE ===""";

    /** Résultat de la phase retrieval (avant génération LLM). */
    public record RagContext(
            List<String> contextChunks,
            List<Map<String, String>> chunkMetadatas,
            List<Double> chunkDistances,
            List<QueryResponse.Source> sources,
            String systemPrompt,
            boolean rerankApplied,
            boolean hybridApplied
    ) {}

    private final ChromaDbClient chromaDbClient;
    private final EmbeddingService embeddingService;
    private final LlmChatClient llmClient;
    private final Optional<RerankerClient> rerankerClient;
    private final Optional<HybridSearchService> hybridSearchService;
    private final Optional<AgenticRagService> agenticRagService;
    private final SpectraProperties props;
    private final ObjectMapper objectMapper;
    private final Duration streamTimeout;

    public RagService(ChromaDbClient chromaDbClient,
                      EmbeddingService embeddingService,
                      LlmChatClient llmClient,
                      Optional<RerankerClient> rerankerClient,
                      Optional<HybridSearchService> hybridSearchService,
                      Optional<AgenticRagService> agenticRagService,
                      SpectraProperties props,
                      ObjectMapper objectMapper) {
        this.chromaDbClient = chromaDbClient;
        this.embeddingService = embeddingService;
        this.llmClient = llmClient;
        this.rerankerClient = rerankerClient;
        this.hybridSearchService = hybridSearchService;
        this.agenticRagService = agenticRagService;
        this.props = props;
        this.objectMapper = objectMapper;
        int timeoutSecs = props.pipeline() != null ? props.pipeline().generationTimeoutSeconds() : 120;
        this.streamTimeout = Duration.ofSeconds(timeoutSecs);
    }

    public QueryResponse query(QueryRequest request) {
        long start = System.currentTimeMillis();
        RagContext ctx = retrieveContext(request);

        if (agenticRagService.isPresent()) {
            return agenticRagService.get().query(
                    request,
                    ctx.contextChunks(), ctx.chunkMetadatas(), ctx.chunkDistances(),
                    ctx.rerankApplied(), ctx.hybridApplied());
        }

        String answer;
        if (ctx.contextChunks().isEmpty()) {
            answer = "Aucun document pertinent trouvé dans la base de connaissances. "
                    + "Veuillez d'abord ingérer des documents via POST /api/ingest.";
        } else {
            answer = llmClient.chat(ctx.systemPrompt(), request.question());
        }

        long duration = System.currentTimeMillis() - start;
        log.info("Query RAG terminée en {}ms, {} sources, hybrid={}, rerank={}",
                duration, ctx.sources().size(), ctx.hybridApplied(), ctx.rerankApplied());

        return new QueryResponse(answer, ctx.sources(), duration, ctx.rerankApplied(), ctx.hybridApplied());
    }

    /**
     * Streaming SSE token par token. Émet : sources → token* → done | error.
     * Contourne le mode agentic (réservé au pipeline non-streaming).
     */
    public Flux<ServerSentEvent<String>> queryStream(QueryRequest request) {
        return Mono.fromCallable(() -> retrieveContext(request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(ctx -> {
                    ServerSentEvent<String> sourcesEvent;
                    try {
                        sourcesEvent = ServerSentEvent.<String>builder()
                                .event("sources")
                                .data(objectMapper.writeValueAsString(ctx.sources()))
                                .build();
                    } catch (JsonProcessingException e) {
                        sourcesEvent = ServerSentEvent.<String>builder()
                                .event("sources").data("[]").build();
                    }

                    Flux<ServerSentEvent<String>> tokenFlux;
                    if (ctx.contextChunks().isEmpty()) {
                        String msg = "Aucun document pertinent trouvé dans la base de connaissances.";
                        tokenFlux = Flux.just(ServerSentEvent.<String>builder()
                                .event("token").data(msg).build());
                    } else {
                        tokenFlux = llmClient.chatStream(
                                        ctx.systemPrompt(), request.question(),
                                        request.temperature(), request.topP())
                                .timeout(streamTimeout)
                                .map(token -> ServerSentEvent.<String>builder()
                                        .event("token").data(token).build());
                    }

                    ServerSentEvent<String> doneEvent = ServerSentEvent.<String>builder()
                            .event("done").data("{}").build();

                    return Flux.concat(Flux.just(sourcesEvent), tokenFlux, Flux.just(doneEvent));
                })
                .onErrorResume(e -> {
                    log.error("Erreur streaming RAG: {}", e.getMessage());
                    String safeMsg = e.getMessage() != null ? e.getMessage().replace("\"", "'") : "Erreur interne";
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data("{\"message\":\"" + safeMsg + "\"}")
                            .build());
                });
    }

    // ── Retrieval ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public RagContext retrieveContext(QueryRequest request) {
        boolean useReranker = rerankerClient.isPresent();
        boolean useHybrid   = hybridSearchService.isPresent();

        List<Float> queryEmbedding = embeddingService.embed(request.question());

        String collectionName = request.collection() != null ? request.collection()
                : (props.chromadb() != null ? props.chromadb().effectiveCollection() : COLLECTION_NAME);
        String collectionId = chromaDbClient.getOrCreateCollection(collectionName);

        int retrieveCount = useReranker
                ? Math.max(request.topCandidates(), request.maxContextChunks())
                : request.maxContextChunks();

        // Retrieval : hybride ou vectoriel pur
        List<String>              allChunks;
        List<Map<String, String>> allMetadatas;
        List<Double>              allDistances;
        List<Float>               allBm25Scores;
        boolean hybridApplied = false;

        if (useHybrid) {
            List<HybridSearchService.HybridChunk> hybridResults =
                    hybridSearchService.get().search(
                            request.question(), queryEmbedding,
                            collectionId, collectionName, retrieveCount);
            allChunks     = new ArrayList<>(hybridResults.size());
            allMetadatas  = new ArrayList<>(hybridResults.size());
            allDistances  = new ArrayList<>(hybridResults.size());
            allBm25Scores = new ArrayList<>(hybridResults.size());
            for (HybridSearchService.HybridChunk hc : hybridResults) {
                allChunks.add(hc.text());
                allMetadatas.add(Map.of("sourceFile", hc.sourceFile()));
                allDistances.add(hc.vectorDistance());
                allBm25Scores.add(hc.bm25Score());
            }
            hybridApplied = !hybridResults.isEmpty();
        } else {
            Map<String, Object> results = chromaDbClient.query(collectionId, queryEmbedding, retrieveCount);
            List<List<String>> documents = (List<List<String>>) results.get("documents");
            List<List<Map<String, String>>> metadatas = (List<List<Map<String, String>>>) results.get("metadatas");
            List<List<Double>> distances = (List<List<Double>>) results.get("distances");
            allChunks     = (documents == null || documents.isEmpty()) ? List.of() : documents.getFirst();
            allMetadatas  = (metadatas == null || metadatas.isEmpty()) ? List.of() : metadatas.getFirst();
            allDistances  = (distances == null || distances.isEmpty()) ? List.of() : distances.getFirst();
            allBm25Scores = null;
        }

        // Re-ranking (optionnel)
        List<String>              contextChunks;
        List<Map<String, String>> chunkMetadatas;
        List<Double>              chunkDistances;
        List<Float>               rerankScores;
        List<Float>               bm25Scores;
        boolean rerankApplied = false;

        if (useReranker && !allChunks.isEmpty()) {
            try {
                List<RerankerClient.RankedResult> ranked = rerankerClient.get()
                        .rerank(request.question(), allChunks, request.maxContextChunks());
                contextChunks  = new ArrayList<>(ranked.size());
                chunkMetadatas = new ArrayList<>(ranked.size());
                chunkDistances = new ArrayList<>(ranked.size());
                rerankScores   = new ArrayList<>(ranked.size());
                bm25Scores     = new ArrayList<>(ranked.size());
                for (RerankerClient.RankedResult r : ranked) {
                    contextChunks.add(allChunks.get(r.index()));
                    chunkMetadatas.add(allMetadatas.get(r.index()));
                    chunkDistances.add(allDistances.get(r.index()));
                    rerankScores.add(r.score());
                    bm25Scores.add(allBm25Scores != null ? allBm25Scores.get(r.index()) : null);
                }
                rerankApplied = true;
                log.info("Re-ranking applied: {} → {} chunks", allChunks.size(), contextChunks.size());
            } catch (Exception e) {
                log.warn("Re-ranker failed, falling back to vector order: {}", e.getMessage());
                contextChunks  = limit(allChunks, request.maxContextChunks());
                chunkMetadatas = limit(allMetadatas, request.maxContextChunks());
                chunkDistances = limit(allDistances, request.maxContextChunks());
                rerankScores   = null;
                bm25Scores     = allBm25Scores != null ? limit(allBm25Scores, request.maxContextChunks()) : null;
            }
        } else {
            contextChunks  = limit(allChunks, request.maxContextChunks());
            chunkMetadatas = limit(allMetadatas, request.maxContextChunks());
            chunkDistances = limit(allDistances, request.maxContextChunks());
            rerankScores   = null;
            bm25Scores     = allBm25Scores != null ? limit(allBm25Scores, request.maxContextChunks()) : null;
        }

        // Construction sources + system prompt
        List<QueryResponse.Source> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < contextChunks.size(); i++) {
            String chunkText  = contextChunks.get(i);
            String sourceFile = chunkMetadatas.get(i).getOrDefault("sourceFile", "inconnu");
            double distance   = chunkDistances.get(i);
            Float rerankScore = (rerankScores != null && i < rerankScores.size()) ? rerankScores.get(i) : null;
            Float bm25Score   = (bm25Scores   != null && i < bm25Scores.size())   ? bm25Scores.get(i)   : null;
            context.append("[Source: ").append(sourceFile).append("]\n").append(chunkText).append("\n\n");
            sources.add(new QueryResponse.Source(
                    chunkText.length() > 200 ? chunkText.substring(0, 200) + "..." : chunkText,
                    sourceFile, distance, rerankScore, bm25Score));
        }

        String systemPrompt = contextChunks.isEmpty() ? null
                : String.format(SYSTEM_PROMPT_TEMPLATE, context);

        return new RagContext(contextChunks, chunkMetadatas, chunkDistances,
                sources, systemPrompt, rerankApplied, hybridApplied);
    }

    private <T> List<T> limit(List<T> list, int max) {
        return list.size() <= max ? list : list.subList(0, max);
    }
}
