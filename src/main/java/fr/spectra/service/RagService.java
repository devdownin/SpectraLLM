package fr.spectra.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import io.micrometer.core.annotation.Timed;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Service RAG principal — orchestre le pipeline de retrieval et de génération.
 *
 * <p><b>Qu'est-ce que le RAG (Retrieval-Augmented Generation) ?</b> Plutôt que de demander
 * au LLM de répondre « de mémoire » (au risque d'halluciner ou d'ignorer vos documents), on
 * procède en deux temps : <i>retrieval</i> — retrouver dans la base vectorielle les passages
 * les plus proches de la question — puis <i>generation</i> — injecter ces passages dans le
 * prompt et demander au LLM de répondre <b>uniquement</b> à partir d'eux. Le modèle reste
 * générique ; c'est le contexte fourni qui le rend expert de votre domaine, avec des réponses
 * sourçables et à jour sans ré-entraînement.</p>
 *
 * <p>Ce service est le chef d'orchestre : la phase retrieval est isolée dans
 * {@code retrieveContext()} (réutilisée par {@code query()} et par le streaming SSE
 * {@code queryStream()}), puis chaque module optionnel ci-dessous l'enrichit. Tous sont
 * activables indépendamment par configuration — un déploiement minimal n'active que le
 * retrieval vectoriel + la génération.</p>
 *
 * <p>Pipeline complet (modules optionnels entre crochets) :</p>
 * <ol>
 *   <li>[Adaptive RAG] Classifie la requête → DIRECT | STANDARD | AGENTIC.</li>
 *   <li>[Conversational RAG] Reformule la question avec l'historique pour le retrieval.</li>
 *   <li>[Long-Context RAG] Si le corpus est petit, charge tout sans retrieval vectoriel.</li>
 *   <li>[Multi-Query] Génère N variantes de la question, retrieval pour chacune, fusion+dédup.</li>
 *   <li>Retrieval : vectoriel pur ou [Hybrid Search] BM25+vecteur avec RRF.</li>
 *   <li>[Re-ranking] Cross-Encoder sur les candidats.</li>
 *   <li>[Semantic Dedup] Suppression des chunks quasi-identiques (Jaccard).</li>
 *   <li>[Corrective RAG] Filtre les chunks non pertinents via grading LLM.</li>
 *   <li>[Context Compression] Extrait les passages pertinents dans chaque chunk.</li>
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
            boolean hybridApplied,
            boolean multiQueryApplied,
            boolean semanticDedupApplied,
            boolean longContextApplied
    ) {}

    /** Résultat d'un retrieval pour une seule requête (interne, avant fusion multi-query). */
    private record SingleQueryResult(
            List<String> chunks,
            List<Map<String, String>> metadatas,
            List<Double> distances,
            List<Float> bm25Scores,
            boolean hybridApplied
    ) {}

    /** Résultat de la fusion des résultats multi-query (interne). */
    private record MultiQueryMerge(
            List<String> chunks,
            List<Map<String, String>> metadatas,
            List<Double> distances,
            List<Float> bm25Scores,
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
    private final Optional<ContextCompressionService> contextCompressionService;
    private final Optional<MultiQueryService> multiQueryService;
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
                      Optional<ContextCompressionService> contextCompressionService,
                      Optional<MultiQueryService> multiQueryService,
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
        this.contextCompressionService = contextCompressionService;
        this.multiQueryService = multiQueryService;
        this.props = props;
        this.objectMapper = objectMapper;
        int timeoutSecs = props.pipeline() != null ? props.pipeline().generationTimeoutSeconds() : 120;
        this.streamTimeout = Duration.ofSeconds(timeoutSecs);
    }

    @Timed(value = "spectra.rag.query", description = "Latence du traitement d'une requête RAG (hors I/O HTTP)")
    public QueryResponse query(QueryRequest request) {
        long start = System.currentTimeMillis();

        // ── 0. Direct LLM mode (RAG désactivé) ────────────────────────────
        if (Boolean.FALSE.equals(request.useRag())) {
            String answer = llmClient.chat(DIRECT_SYSTEM_PROMPT, request.question());
            long duration = System.currentTimeMillis() - start;
            return new QueryResponse(answer, List.of(), duration,
                    false, false, false, 0, null,
                    false, false, false, "DIRECT",
                    false, false, false, false);
        }

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
                        false, false, false, ragStrategy,
                        false, false, false, false);
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
                ctx = rebuildContext(filtered, ctx.rerankApplied(), ctx.hybridApplied(),
                        ctx.multiQueryApplied(), ctx.semanticDedupApplied(), ctx.longContextApplied());
                correctiveApplied = true;
            }
        }

        // ── 4.5. Context Compression : extraction des passages pertinents ──
        boolean compressionApplied = false;

        if (contextCompressionService.isPresent() && !ctx.contextChunks().isEmpty()) {
            ContextCompressionService.CompressionResult cr =
                    contextCompressionService.get().compress(request.question(), ctx.contextChunks());
            if (!cr.keptIndices().isEmpty()) {
                ctx = buildRagContext(
                        cr.compressedTexts(),
                        filterByIndices(ctx.chunkMetadatas(), cr.keptIndices()),
                        filterByIndices(ctx.chunkDistances(), cr.keptIndices()),
                        ctx.rerankScores()  != null ? filterByIndices(ctx.rerankScores(), cr.keptIndices())  : null,
                        ctx.bm25Scores()    != null ? filterByIndices(ctx.bm25Scores(), cr.keptIndices())    : null,
                        ctx.rerankApplied(), ctx.hybridApplied(),
                        ctx.multiQueryApplied(), ctx.semanticDedupApplied(), ctx.longContextApplied());
                compressionApplied = true;
            } else {
                log.warn("Context compression : aucun passage conservé, contexte original maintenu");
            }
        }

        // ── 5. Agentic RAG ─────────────────────────────────────────────────
        // Déclenché uniquement quand le routage adaptatif a classé la requête comme
        // AGENTIC (forceAgentic) ET que le service agentique est disponible.
        if (agenticRagService.isPresent() && forceAgentic) {
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
                        ragStrategy.equals("STANDARD") ? "AGENTIC" : ragStrategy,
                        ctx.multiQueryApplied(), false, ctx.semanticDedupApplied(), ctx.longContextApplied());
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
        log.info("Query RAG en {}ms, {} sources, hybrid={}, rerank={}, multiQuery={}, semanticDedup=N/A, "
                + "longContext={}, corrective={}, compression={}, conversational={}, selfRag={}",
                duration, ctx.sources().size(), ctx.hybridApplied(), ctx.rerankApplied(),
                ctx.multiQueryApplied(), ctx.longContextApplied(),
                correctiveApplied, compressionApplied, conversationalApplied, selfRagApplied);

        return new QueryResponse(answer, ctx.sources(), duration,
                ctx.rerankApplied(), ctx.hybridApplied(), false, 0, null,
                conversationalApplied, correctiveApplied, selfRagApplied, ragStrategy,
                ctx.multiQueryApplied(), compressionApplied, ctx.semanticDedupApplied(), ctx.longContextApplied());
    }

    /** Tuple interne pour la phase de setup du streaming. */
    private record StreamSetup(RagContext ctx, boolean conversationalApplied,
                               boolean correctiveApplied, boolean compressionApplied) {}

    /**
     * Streaming SSE token par token. Émet : sources → token* → done | error.
     * Le mode agentic, adaptive et self-RAG sont réservés au pipeline non-streaming.
     * Le Conversational RAG (contextualisation légère) est cependant appliqué.
     * L'événement {@code done} contient un JSON avec les métadonnées du pipeline.
     */
    public Flux<ServerSentEvent<String>> queryStream(QueryRequest request) {
        // Direct LLM mode (RAG disabled by caller)
        if (Boolean.FALSE.equals(request.useRag())) {
            return Mono.fromCallable(() -> llmClient.chatStream(
                            DIRECT_SYSTEM_PROMPT, request.question(),
                            request.temperature(), request.topP()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(tokenFlux -> {
                        ServerSentEvent<String> sourcesEvent = ServerSentEvent.<String>builder()
                                .event("sources").data("[]").build();
                        Flux<ServerSentEvent<String>> tokens = tokenFlux
                                .timeout(streamTimeout)
                                .map(t -> ServerSentEvent.<String>builder().event("token").data(t).build());
                        ServerSentEvent<String> doneEvent = ServerSentEvent.<String>builder()
                                .event("done")
                                .data("{\"conversationalApplied\":false,\"correctiveApplied\":false,"
                                        + "\"selfRagApplied\":false,\"ragStrategy\":\"DIRECT\","
                                        + "\"rerankApplied\":false,\"hybridSearchApplied\":false,"
                                        + "\"multiQueryApplied\":false,\"semanticDedupApplied\":false,"
                                        + "\"longContextApplied\":false,\"compressionApplied\":false}")
                                .build();
                        return Flux.concat(Flux.just(sourcesEvent), tokens, Flux.just(doneEvent));
                    })
                    .onErrorResume(e -> {
                        log.error("Erreur streaming direct: {}", e.getMessage());
                        String safeMsg = e.getMessage() != null ? e.getMessage().replace("\"", "'") : "Erreur interne";
                        return Flux.just(ServerSentEvent.<String>builder()
                                .event("error").data("{\"message\":\"" + safeMsg + "\"}").build());
                    });
        }

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
                    RagContext ctx = retrieveContext(request, retrievalQuestion);

                    boolean correctiveApplied = false;
                    if (correctiveRagService.isPresent() && !ctx.contextChunks().isEmpty()) {
                        List<Integer> keptIndices = correctiveRagService.get()
                                .gradeChunks(request.question(), ctx.contextChunks());
                        if (keptIndices.size() < ctx.contextChunks().size()) {
                            CorrectiveRagService.FilteredContext filtered = correctiveRagService.get().filterByIndices(
                                    keptIndices,
                                    ctx.contextChunks(), ctx.chunkMetadatas(), ctx.chunkDistances(),
                                    ctx.rerankScores(), ctx.bm25Scores());
                            ctx = rebuildContext(filtered, ctx.rerankApplied(), ctx.hybridApplied(),
                                    ctx.multiQueryApplied(), ctx.semanticDedupApplied(), ctx.longContextApplied());
                            correctiveApplied = true;
                        }
                    }

                    boolean compressionApplied = false;
                    if (contextCompressionService.isPresent() && !ctx.contextChunks().isEmpty()) {
                        ContextCompressionService.CompressionResult cr =
                                contextCompressionService.get().compress(request.question(), ctx.contextChunks());
                        if (!cr.keptIndices().isEmpty()) {
                            ctx = buildRagContext(
                                    cr.compressedTexts(),
                                    filterByIndices(ctx.chunkMetadatas(), cr.keptIndices()),
                                    filterByIndices(ctx.chunkDistances(), cr.keptIndices()),
                                    ctx.rerankScores()  != null ? filterByIndices(ctx.rerankScores(), cr.keptIndices())  : null,
                                    ctx.bm25Scores()    != null ? filterByIndices(ctx.bm25Scores(), cr.keptIndices())    : null,
                                    ctx.rerankApplied(), ctx.hybridApplied(),
                                    ctx.multiQueryApplied(), ctx.semanticDedupApplied(), ctx.longContextApplied());
                            compressionApplied = true;
                        }
                    }

                    return new StreamSetup(ctx, conversationalApplied, correctiveApplied, compressionApplied);
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
                            "{\"conversationalApplied\":%b,\"correctiveApplied\":%b,"
                            + "\"selfRagApplied\":false,\"ragStrategy\":\"STANDARD\","
                            + "\"rerankApplied\":%b,\"hybridSearchApplied\":%b,"
                            + "\"multiQueryApplied\":%b,\"semanticDedupApplied\":%b,"
                            + "\"longContextApplied\":%b,\"compressionApplied\":%b}",
                            setup.conversationalApplied(), setup.correctiveApplied(),
                            ctx.rerankApplied(), ctx.hybridApplied(),
                            ctx.multiQueryApplied(), ctx.semanticDedupApplied(), ctx.longContextApplied(),
                            setup.compressionApplied());
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
    public RagContext retrieveContext(QueryRequest request, String retrievalQuestion) {
        boolean useReranker = rerankerClient.isPresent();

        String collectionName = request.collection() != null ? request.collection()
                : (props.chromadb() != null ? props.chromadb().effectiveCollection() : COLLECTION_NAME);
        String collectionId = chromaDbClient.getOrCreateCollection(collectionName);

        int retrieveCount = useReranker
                ? Math.max(request.topCandidates(), request.maxContextChunks())
                : request.maxContextChunks();

        // ── 1. Long-context bypass ─────────────────────────────────────────
        if (props.longContextRag() != null && props.longContextRag().isEnabled()) {
            int maxChunks = props.longContextRag().effectiveMaxCollectionChunks();
            try {
                int collectionSize = chromaDbClient.count(collectionId);
                if (collectionSize > 0 && collectionSize <= maxChunks) {
                    log.info("Long-context RAG : {} chunks ≤ {} → chargement intégral sans retrieval vectoriel",
                            collectionSize, maxChunks);
                    return buildFullContextResult(collectionId);
                }
            } catch (Exception e) {
                log.warn("Long-context RAG : erreur lors du comptage, fallback retrieval standard — {}", e.getMessage());
            }
        }

        // ── 2. Retrieval : single-query ou multi-query ─────────────────────
        List<String>              allChunks;
        List<Map<String, String>> allMetadatas;
        List<Double>              allDistances;
        List<Float>               allBm25Scores;
        boolean hybridApplied = false;
        boolean multiQueryApplied = false;

        if (multiQueryService.isPresent()) {
            try {
                List<String> queries = multiQueryService.get().generateQueries(retrievalQuestion);
                if (queries.size() > 1) {
                    MultiQueryMerge merged = executeMultiQueryRetrieval(
                            queries, collectionId, collectionName, retrieveCount);
                    allChunks      = merged.chunks();
                    allMetadatas   = merged.metadatas();
                    allDistances   = merged.distances();
                    allBm25Scores  = merged.bm25Scores();
                    hybridApplied  = merged.hybridApplied();
                    multiQueryApplied = true;
                } else {
                    SingleQueryResult r = executeSingleQuery(retrievalQuestion, collectionId, collectionName, retrieveCount);
                    allChunks = r.chunks(); allMetadatas = r.metadatas();
                    allDistances = r.distances(); allBm25Scores = r.bm25Scores();
                    hybridApplied = r.hybridApplied();
                }
            } catch (Exception e) {
                log.warn("Multi-query échoué, fallback retrieval simple: {}", e.getMessage());
                SingleQueryResult r = executeSingleQuery(retrievalQuestion, collectionId, collectionName, retrieveCount);
                allChunks = r.chunks(); allMetadatas = r.metadatas();
                allDistances = r.distances(); allBm25Scores = r.bm25Scores();
                hybridApplied = r.hybridApplied();
            }
        } else {
            SingleQueryResult r = executeSingleQuery(retrievalQuestion, collectionId, collectionName, retrieveCount);
            allChunks = r.chunks(); allMetadatas = r.metadatas();
            allDistances = r.distances(); allBm25Scores = r.bm25Scores();
            hybridApplied = r.hybridApplied();
        }

        // ── 3. Re-ranking ──────────────────────────────────────────────────
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
                    if (r.index() < 0 || r.index() >= allChunks.size()) {
                        log.warn("Re-ranker returned out-of-bounds index {} (allChunks.size={}), skipping",
                                r.index(), allChunks.size());
                        continue;
                    }
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

        // ── 4. Semantic deduplication ──────────────────────────────────────
        boolean semanticDedupApplied = false;
        if (props.semanticDedup() != null && props.semanticDedup().isEnabled() && contextChunks.size() > 1) {
            double threshold = props.semanticDedup().effectiveSimilarityThreshold();
            List<Integer> keptIndices = deduplicateSemantically(contextChunks, threshold);
            if (keptIndices.size() < contextChunks.size()) {
                int before = contextChunks.size();
                contextChunks  = filterByIndices(contextChunks, keptIndices);
                chunkMetadatas = filterByIndices(chunkMetadatas, keptIndices);
                chunkDistances = filterByIndices(chunkDistances, keptIndices);
                rerankScores   = rerankScores != null ? filterByIndices(rerankScores, keptIndices) : null;
                bm25Scores     = bm25Scores   != null ? filterByIndices(bm25Scores, keptIndices)   : null;
                semanticDedupApplied = true;
                log.info("Semantic dedup : {} → {} chunks (seuil={})", before, contextChunks.size(), threshold);
            }
        }

        return buildRagContext(contextChunks, chunkMetadatas, chunkDistances,
                rerankScores, bm25Scores, rerankApplied, hybridApplied, multiQueryApplied, semanticDedupApplied, false);
    }

    // ── Helpers privés ─────────────────────────────────────────────────────────

    /**
     * Reconstruit un {@link RagContext} après filtrage par le Corrective RAG.
     */
    private RagContext rebuildContext(CorrectiveRagService.FilteredContext filtered,
                                      boolean rerankApplied, boolean hybridApplied,
                                      boolean multiQueryApplied, boolean semanticDedupApplied,
                                      boolean longContextApplied) {
        return buildRagContext(
                filtered.chunks(), filtered.metadatas(), filtered.distances(),
                filtered.rerankScores(), filtered.bm25Scores(),
                rerankApplied, hybridApplied, multiQueryApplied, semanticDedupApplied, longContextApplied);
    }

    private RagContext buildRagContext(List<String> chunks, List<Map<String, String>> metadatas,
                                       List<Double> distances, List<Float> rerankScores,
                                       List<Float> bm25Scores, boolean rerankApplied, boolean hybridApplied,
                                       boolean multiQueryApplied, boolean semanticDedupApplied,
                                       boolean longContextApplied) {
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
                sources, systemPrompt, rerankApplied, hybridApplied, multiQueryApplied, semanticDedupApplied, longContextApplied);
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

    // ── Helpers retrieval ──────────────────────────────────────────────────────

    /**
     * Exécute un retrieval vectoriel ou hybride pour une seule requête.
     */
    @SuppressWarnings("unchecked")
    private SingleQueryResult executeSingleQuery(String question, String collectionId,
                                                  String collectionName, int retrieveCount) {
        List<Float> embedding = embeddingService.embed(question);

        if (hybridSearchService.isPresent()) {
            List<HybridSearchService.HybridChunk> results =
                    hybridSearchService.get().search(question, embedding, collectionId, collectionName, retrieveCount);
            List<String>              chunks    = new ArrayList<>(results.size());
            List<Map<String, String>> metadatas = new ArrayList<>(results.size());
            List<Double>              distances = new ArrayList<>(results.size());
            List<Float>               bm25      = new ArrayList<>(results.size());
            for (HybridSearchService.HybridChunk hc : results) {
                chunks.add(hc.text());
                metadatas.add(Map.of("sourceFile", hc.sourceFile()));
                distances.add(hc.vectorDistance());
                bm25.add(hc.bm25Score());
            }
            return new SingleQueryResult(chunks, metadatas, distances, bm25, !results.isEmpty());
        } else {
            Map<String, Object> results = chromaDbClient.query(collectionId, embedding, retrieveCount);
            List<List<String>>              documents = (List<List<String>>) results.get("documents");
            List<List<Map<String, String>>> metadatas = (List<List<Map<String, String>>>) results.get("metadatas");
            List<List<Double>>              distances = (List<List<Double>>) results.get("distances");
            return new SingleQueryResult(
                    (documents == null || documents.isEmpty()) ? List.of() : documents.getFirst(),
                    (metadatas == null || metadatas.isEmpty()) ? List.of() : metadatas.getFirst(),
                    (distances == null || distances.isEmpty()) ? List.of() : distances.getFirst(),
                    null, false);
        }
    }

    /**
     * Exécute le retrieval pour chaque requête de la liste, fusionne les résultats
     * en déduplication exacte sur le texte du chunk (premier trouvé = meilleur score).
     * Le résultat est limité à {@code retrieveCount} chunks triés par distance croissante.
     */
    private MultiQueryMerge executeMultiQueryRetrieval(List<String> queries, String collectionId,
                                                        String collectionName, int retrieveCount) {
        // LinkedHashMap pour conserver l'ordre d'insertion (question originale en premier)
        Map<String, Integer> indexByText = new LinkedHashMap<>();
        List<String>              mergedChunks    = new ArrayList<>();
        List<Map<String, String>> mergedMetadatas = new ArrayList<>();
        List<Double>              mergedDistances = new ArrayList<>();
        List<Float>               mergedBm25      = new ArrayList<>();
        boolean hybridApplied = false;
        boolean trackBm25 = true; // désactivé si un résultat n'a pas de scores BM25

        for (String query : queries) {
            SingleQueryResult r = executeSingleQuery(query, collectionId, collectionName, retrieveCount);
            hybridApplied = hybridApplied || r.hybridApplied();
            if (r.bm25Scores() == null) trackBm25 = false;

            for (int i = 0; i < r.chunks().size(); i++) {
                String text = r.chunks().get(i);
                if (!indexByText.containsKey(text)) {
                    indexByText.put(text, mergedChunks.size());
                    mergedChunks.add(text);
                    mergedMetadatas.add(r.metadatas().get(i));
                    mergedDistances.add(r.distances().get(i));
                    if (trackBm25 && r.bm25Scores() != null) mergedBm25.add(r.bm25Scores().get(i));
                } else {
                    // Conserve la meilleure distance (plus faible = plus proche) pour ce chunk
                    int existingIdx = indexByText.get(text);
                    if (r.distances().get(i) < mergedDistances.get(existingIdx)) {
                        mergedDistances.set(existingIdx, r.distances().get(i));
                    }
                }
            }
        }

        // Trier par distance croissante et limiter à retrieveCount
        List<Integer> sortedIdx = new ArrayList<>();
        for (int i = 0; i < mergedChunks.size(); i++) sortedIdx.add(i);
        sortedIdx.sort((a, b) -> Double.compare(mergedDistances.get(a), mergedDistances.get(b)));
        List<Integer> topIdx = sortedIdx.subList(0, Math.min(retrieveCount, sortedIdx.size()));

        log.info("Multi-query : {} chunks uniques fusionnés depuis {} requêtes → {} retenus",
                mergedChunks.size(), queries.size(), topIdx.size());

        return new MultiQueryMerge(
                filterByIndices(mergedChunks, topIdx),
                filterByIndices(mergedMetadatas, topIdx),
                filterByIndices(mergedDistances, topIdx),
                trackBm25 && !mergedBm25.isEmpty() ? filterByIndices(mergedBm25, topIdx) : null,
                hybridApplied);
    }

    /**
     * Charge tous les documents d'une collection pour le Long-Context RAG bypass.
     */
    @SuppressWarnings("unchecked")
    private RagContext buildFullContextResult(String collectionId) {
        Map<String, Object> allDocs = chromaDbClient.getAllDocuments(collectionId);
        List<String>              documents = (List<String>) allDocs.get("documents");
        List<Map<String, String>> metadatas = (List<Map<String, String>>) allDocs.get("metadatas");

        if (documents == null || documents.isEmpty()) {
            log.warn("Long-context RAG : collection vide, aucun document chargé");
            return buildRagContext(List.of(), List.of(), List.of(), null, null,
                    false, false, false, false, true);
        }

        // Distance 0.0 : tous les chunks sont directement pertinents (pas de filtrage vectoriel)
        List<Double> distances = new ArrayList<>(Collections.nCopies(documents.size(), 0.0));
        return buildRagContext(documents, metadatas != null ? metadatas : List.of(), distances,
                null, null, false, false, false, false, true);
    }

    // ── Helpers déduplication sémantique ──────────────────────────────────────

    /**
     * Retourne les indices des chunks à conserver après déduplication sémantique.
     * Pour chaque chunk, si sa similarité Jaccard avec un chunk déjà conservé dépasse
     * {@code threshold}, il est éliminé (le premier chunk = score le plus élevé est gardé).
     */
    private List<Integer> deduplicateSemantically(List<String> chunks, double threshold) {
        // Pré-tokenise chaque chunk une seule fois (évite une re-tokenisation O(n²)).
        List<Set<String>> wordSets = new ArrayList<>(chunks.size());
        for (String chunk : chunks) wordSets.add(tokenizeToSet(chunk));

        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            boolean duplicate = false;
            for (int j : kept) {
                if (jaccardSimilarity(wordSets.get(i), wordSets.get(j)) >= threshold) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) kept.add(i);
        }
        return kept;
    }

    private static Set<String> tokenizeToSet(String text) {
        return new HashSet<>(Arrays.asList(text.toLowerCase(Locale.ROOT).split("\\s+")));
    }

    private static double jaccardSimilarity(Set<String> wordsA, Set<String> wordsB) {
        if (wordsA.isEmpty() && wordsB.isEmpty()) return 1.0;
        int intersection = 0;
        // Itère sur le plus petit ensemble pour limiter les lookups.
        Set<String> smaller = wordsA.size() <= wordsB.size() ? wordsA : wordsB;
        Set<String> larger  = smaller == wordsA ? wordsB : wordsA;
        for (String w : smaller) {
            if (larger.contains(w)) intersection++;
        }
        int union = wordsA.size() + wordsB.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    // ── Helpers listes parallèles ──────────────────────────────────────────────

    private static <T> List<T> filterByIndices(List<T> list, List<Integer> indices) {
        List<T> result = new ArrayList<>(indices.size());
        for (int idx : indices) result.add(list.get(idx));
        return result;
    }
}
