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
 * Service RAG principal — orchestre le pipeline de retrieval et de génération.
 *
 * <p>Pipeline complet (modules optionnels entre crochets) :</p>
 * <ol>
 *   <li>[Adaptive RAG] Classifie la requête → DIRECT | STANDARD | AGENTIC.</li>
 *   <li>[Conversational RAG] Reformule la question avec l'historique pour le retrieval.</li>
 *   <li>Retrieval : vectoriel pur ou [Hybrid Search] BM25+vecteur avec RRF.</li>
 *   <li>[Re-ranking] Cross-Encoder sur les candidats.</li>
 *   <li>[Corrective RAG] Filtre les chunks non pertinents via grading LLM.</li>
 *   <li>[Agentic RAG] Boucle ReAct si la stratégie est AGENTIC.</li>
 *   <li>[Self-RAG] Génère et auto-évalue ; raffine si qualité insuffisante.</li>
 *   <li>Génération standard via le LLM.</li>
 * </ol>
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

    private static final String DIRECT_SYSTEM_PROMPT =
            "Tu es un assistant utile. Réponds de manière concise et précise.";

    /** Résultat de la phase retrieval (avant génération LLM). */
    public record RagContext(
            List<String> contextChunks,
            List<Map<String, String>> chunkMetadatas,
            List<Double> chunkDistances,
            List<Float> rerankScores,
            List<Float> bm25Scores,
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
    private final Optional<ConversationalRagService> conversationalRagService;
    private final Optional<CorrectiveRagService> correctiveRagService;
    private final Optional<AdaptiveRagService> adaptiveRagService;
    private final Optional<SelfRagService> selfRagService;
    private final SpectraProperties props;
    private final ObjectMapper objectMapper;
    private final Duration streamTimeout;

    public RagService(ChromaDbClient chromaDbClient,
                      EmbeddingService embeddingService,
                      LlmChatClient llmClient,
                      Optional<RerankerClient> rerankerClient,
                      Optional<HybridSearchService> hybridSearchService,
                      Optional<AgenticRagService> agenticRagService,
                      Optional<ConversationalRagService> conversationalRagService,
                      Optional<CorrectiveRagService> correctiveRagService,
                      Optional<AdaptiveRagService> adaptiveRagService,
                      Optional<SelfRagService> selfRagService,
                      SpectraProperties props,
                      ObjectMapper objectMapper) {
        this.chromaDbClient = chromaDbClient;
        this.embeddingService = embeddingService;
        this.llmClient = llmClient;
        this.rerankerClient = rerankerClient;
        this.hybridSearchService = hybridSearchService;
        this.agenticRagService = agenticRagService;
        this.conversationalRagService = conversationalRagService;
        this.correctiveRagService = correctiveRagService;
        this.adaptiveRagService = adaptiveRagService;
        this.selfRagService = selfRagService;
        this.props = props;
        this.objectMapper = objectMapper;
        int timeoutSecs = props.pipeline() != null ? props.pipeline().generationTimeoutSeconds() : 120;
        this.streamTimeout = Duration.ofSeconds(timeoutSecs);
    }

    public QueryResponse query(QueryRequest request) {
        long start = System.currentTimeMillis();

        // ── 1. Adaptive RAG : routing ──────────────────────────────────────
        String ragStrategy = "STANDARD";
        boolean forceAgentic = false;

        if (adaptiveRagService.isPresent()) {
            AdaptiveRagService.RagStrategy strategy = adaptiveRagService.get().classifyQuery(request.question());
            ragStrategy = strategy.name();

            if (strategy == AdaptiveRagService.RagStrategy.DIRECT) {
                String answer = llmClient.chat(DIRECT_SYSTEM_PROMPT, request.question());
                long duration = System.currentTimeMillis() - start;
                log.info("Adaptive RAG DIRECT en {}ms", duration);
                return new QueryResponse(answer, List.of(), duration,
                        false, false, false, 0, null,
                        false, false, false, ragStrategy);
            }
            if (strategy == AdaptiveRagService.RagStrategy.AGENTIC) {
                forceAgentic = true;
            }
        }

        // ── 2. Conversational RAG : contextualisation de la question ──────
        String retrievalQuestion = request.question();
        boolean conversationalApplied = false;

        if (conversationalRagService.isPresent()
                && request.conversationHistory() != null
                && !request.conversationHistory().isEmpty()) {
            String standalone = conversationalRagService.get()
                    .contextualizeQuestion(request.question(), request.conversationHistory());
            if (!standalone.equals(request.question())) {
                retrievalQuestion = standalone;
                conversationalApplied = true;
            }
        }

        // ── 3. Retrieval ───────────────────────────────────────────────────
        RagContext ctx = retrieveContext(request, retrievalQuestion);

        // ── 4. Corrective RAG : filtrage des chunks non pertinents ─────────
        boolean correctiveApplied = false;

        if (correctiveRagService.isPresent() && !ctx.contextChunks().isEmpty()) {
            List<Integer> keptIndices = correctiveRagService.get()
                    .gradeChunks(request.question(), ctx.contextChunks());

            if (keptIndices.size() < ctx.contextChunks().size()) {
                CorrectiveRagService.FilteredContext filtered = correctiveRagService.get().filterByIndices(
                        keptIndices,
                        ctx.contextChunks(), ctx.chunkMetadatas(), ctx.chunkDistances(),
                        ctx.rerankScores(), ctx.bm25Scores());
                ctx = rebuildContext(filtered, ctx.rerankApplied(), ctx.hybridApplied());
                correctiveApplied = true;
            }
        }

        // ── 5. Agentic RAG ─────────────────────────────────────────────────
        if (agenticRagService.isPresent() || forceAgentic) {
            if (agenticRagService.isPresent()) {
                QueryResponse agenticResp = agenticRagService.get().query(
                        request,
                        ctx.contextChunks(), ctx.chunkMetadatas(), ctx.chunkDistances(),
                        ctx.rerankApplied(), ctx.hybridApplied());
                long duration = System.currentTimeMillis() - start;
                return new QueryResponse(
                        agenticResp.answer(), agenticResp.sources(), duration,
                        agenticResp.rerankApplied(), agenticResp.hybridSearchApplied(),
                        true, agenticResp.agenticIterations(), agenticResp.agenticStopReason(),
                        conversationalApplied, correctiveApplied, false,
                        ragStrategy.equals("STANDARD") ? "AGENTIC" : ragStrategy);
            }
        }

        // ── 6. Génération (standard ou Self-RAG) ───────────────────────────
        String answer;
        boolean selfRagApplied = false;

        if (ctx.contextChunks().isEmpty()) {
            answer = "Aucun document pertinent trouvé dans la base de connaissances. "
                    + "Veuillez d'abord ingérer des documents via POST /api/ingest.";
        } else {
            String systemPrompt = ctx.systemPrompt();
            String userMessage  = buildUserMessage(request, conversationalApplied);

            if (selfRagService.isPresent()) {
                SelfRagService.SelfRagResult result = selfRagService.get()
                        .reflect(request.question(), ctx.contextChunks(), systemPrompt, userMessage);
                answer = result.answer();
                selfRagApplied = result.reflectionApplied();
            } else {
                answer = llmClient.chat(systemPrompt, userMessage);
            }
        }

        long duration = System.currentTimeMillis() - start;
        log.info("Query RAG en {}ms, {} sources, hybrid={}, rerank={}, conversational={}, corrective={}, selfRag={}",
                duration, ctx.sources().size(), ctx.hybridApplied(), ctx.rerankApplied(),
                conversationalApplied, correctiveApplied, selfRagApplied);

        return new QueryResponse(answer, ctx.sources(), duration,
                ctx.rerankApplied(), ctx.hybridApplied(), false, 0, null,
                conversationalApplied, correctiveApplied, selfRagApplied, ragStrategy);
    }

    /** Tuple interne pour la phase de setup du streaming. */
    private record StreamSetup(RagContext ctx, boolean conversationalApplied) {}

    /**
     * Streaming SSE token par token. Émet : sources → token* → done | error.
     * Le mode agentic, adaptive et self-RAG sont réservés au pipeline non-streaming.
     * Le Conversational RAG (contextualisation légère) est cependant appliqué.
     * L'événement {@code done} contient un JSON avec les métadonnées du pipeline.
     */
    public Flux<ServerSentEvent<String>> queryStream(QueryRequest request) {
        return Mono.fromCallable(() -> {
                    String retrievalQuestion = request.question();
                    boolean conversationalApplied = false;
                    if (conversationalRagService.isPresent()
                            && request.conversationHistory() != null
                            && !request.conversationHistory().isEmpty()) {
                        String standalone = conversationalRagService.get()
                                .contextualizeQuestion(request.question(), request.conversationHistory());
                        if (!standalone.equals(request.question())) {
                            retrievalQuestion = standalone;
                            conversationalApplied = true;
                        }
                    }
                    return new StreamSetup(retrieveContext(request, retrievalQuestion), conversationalApplied);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(setup -> {
                    RagContext ctx = setup.ctx();

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
                        String userMessage = buildUserMessage(request, setup.conversationalApplied());
                        tokenFlux = llmClient.chatStream(
                                        ctx.systemPrompt(), userMessage,
                                        request.temperature(), request.topP())
                                .timeout(streamTimeout)
                                .map(token -> ServerSentEvent.<String>builder()
                                        .event("token").data(token).build());
                    }

                    String doneMeta = String.format(
                            "{\"conversationalApplied\":%b,\"correctiveApplied\":false,"
                            + "\"selfRagApplied\":false,\"ragStrategy\":\"STANDARD\","
                            + "\"rerankApplied\":%b,\"hybridSearchApplied\":%b}",
                            setup.conversationalApplied(), ctx.rerankApplied(), ctx.hybridApplied());
                    ServerSentEvent<String> doneEvent = ServerSentEvent.<String>builder()
                            .event("done").data(doneMeta).build();

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

    // ── Retrieval ──────────────────────────────────────────────────────────────

    /** Retrieval avec la question originale de la requête. */
    public RagContext retrieveContext(QueryRequest request) {
        return retrieveContext(request, request.question());
    }

    /**
     * Retrieval avec une question de recherche potentiellement reformulée
     * (ex. par le Conversational RAG ou l'Agentic RAG).
     */
    @SuppressWarnings("unchecked")
    public RagContext retrieveContext(QueryRequest request, String retrievalQuestion) {
        boolean useReranker = rerankerClient.isPresent();
        boolean useHybrid   = hybridSearchService.isPresent();

        List<Float> queryEmbedding = embeddingService.embed(retrievalQuestion);

        String collectionName = request.collection() != null ? request.collection()
                : (props.chromadb() != null ? props.chromadb().effectiveCollection() : COLLECTION_NAME);
        String collectionId = chromaDbClient.getOrCreateCollection(collectionName);

        int retrieveCount = useReranker
                ? Math.max(request.topCandidates(), request.maxContextChunks())
                : request.maxContextChunks();

        List<String>              allChunks;
        List<Map<String, String>> allMetadatas;
        List<Double>              allDistances;
        List<Float>               allBm25Scores;
        boolean hybridApplied = false;

        if (useHybrid) {
            List<HybridSearchService.HybridChunk> hybridResults =
                    hybridSearchService.get().search(
                            retrievalQuestion, queryEmbedding,
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
                        .rerank(retrievalQuestion, allChunks, request.maxContextChunks());
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

        return buildRagContext(contextChunks, chunkMetadatas, chunkDistances,
                rerankScores, bm25Scores, rerankApplied, hybridApplied);
    }

    // ── Helpers privés ─────────────────────────────────────────────────────────

    /**
     * Reconstruit un {@link RagContext} après filtrage par le Corrective RAG.
     */
    private RagContext rebuildContext(CorrectiveRagService.FilteredContext filtered,
                                      boolean rerankApplied, boolean hybridApplied) {
        return buildRagContext(
                filtered.chunks(), filtered.metadatas(), filtered.distances(),
                filtered.rerankScores(), filtered.bm25Scores(),
                rerankApplied, hybridApplied);
    }

    private RagContext buildRagContext(List<String> chunks, List<Map<String, String>> metadatas,
                                       List<Double> distances, List<Float> rerankScores,
                                       List<Float> bm25Scores, boolean rerankApplied, boolean hybridApplied) {
        List<QueryResponse.Source> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();

        for (int i = 0; i < chunks.size(); i++) {
            String chunkText  = chunks.get(i);
            String sourceFile = metadatas.get(i).getOrDefault("sourceFile", "inconnu");
            double distance   = distances.get(i);
            Float rerankScore = (rerankScores != null && i < rerankScores.size()) ? rerankScores.get(i) : null;
            Float bm25Score   = (bm25Scores   != null && i < bm25Scores.size())   ? bm25Scores.get(i)   : null;
            context.append("[Source: ").append(sourceFile).append("]\n").append(chunkText).append("\n\n");
            sources.add(new QueryResponse.Source(
                    chunkText.length() > 200 ? chunkText.substring(0, 200) + "..." : chunkText,
                    sourceFile, distance, rerankScore, bm25Score));
        }

        String systemPrompt = chunks.isEmpty() ? null : String.format(SYSTEM_PROMPT_TEMPLATE, context);

        return new RagContext(chunks, metadatas, distances, rerankScores, bm25Scores,
                sources, systemPrompt, rerankApplied, hybridApplied);
    }

    /**
     * Construit le message utilisateur pour la génération.
     * Si le Conversational RAG est actif et qu'il y a un historique, préfixe l'historique.
     */
    private String buildUserMessage(QueryRequest request, boolean conversationalApplied) {
        if (conversationalApplied
                && conversationalRagService.isPresent()
                && request.conversationHistory() != null
                && !request.conversationHistory().isEmpty()) {
            return conversationalRagService.get()
                    .buildUserMessageWithHistory(request.question(), request.conversationHistory());
        }
        return request.question();
    }

    private <T> List<T> limit(List<T> list, int max) {
        return list.size() <= max ? list : list.subList(0, max);
    }
}
