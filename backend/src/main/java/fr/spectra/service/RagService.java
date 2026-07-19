package fr.spectra.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import io.micrometer.core.annotation.Timed;
import fr.spectra.dto.QueryRequest;
import fr.spectra.dto.QueryResponse;
import fr.spectra.dto.RagOverrides;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    // Persona canonique (cf. AssistantPersona) — identique à celle du fine-tuning — suivie des
    // consignes RAG. Servir le modèle fine-tuné sous une autre persona dégraderait son apport.
    private static final String SYSTEM_PROMPT_TEMPLATE =
            fr.spectra.model.AssistantPersona.SYSTEM_PROMPT + "\n"
            + """
            Réponds de manière précise et concise en te basant UNIQUEMENT sur le contexte fourni ci-dessous.
            Si le contexte ne contient pas l'information demandée, dis-le clairement.
            Ne fabrique pas d'information.

            === CONTEXTE ===
            %s
            === FIN DU CONTEXTE ===""";

    // Mode direct (sans contexte récupéré) : on conserve la même persona que l'entraînement.
    private static final String DIRECT_SYSTEM_PROMPT =
            fr.spectra.model.AssistantPersona.SYSTEM_PROMPT
            + " Réponds de manière concise et précise.";

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
            /** Scores RRF de la recherche hybride, alignés sur {@code chunks} ({@code null} en vectoriel pur). */
            List<Double> rrfScores,
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
        // Les surcharges éventuellement portées par la requête (toggles Playground) priment ;
        // l'appel explicite query(request, overrides) reste la voie de l'ablation.
        return query(request, request.overrides());
    }

    /**
     * Variante avec <b>surcharges par requête</b> : chaque module optionnel peut être forcé
     * actif/inactif (utilisée par l'ablation pour mesurer l'apport de chaque option). Un override
     * à {@code true} n'a d'effet que si le module est disponible ; {@code null} = défaut de config.
     */
    public QueryResponse query(QueryRequest request, RagOverrides overrides) {
        RagOverrides ov = overrides != null ? overrides : RagOverrides.NONE;
        long start = System.currentTimeMillis();

        // ── 0. Direct LLM mode (RAG désactivé) ────────────────────────────
        if (Boolean.FALSE.equals(request.useRag())) {
            String answer = llmClient.chat(DIRECT_SYSTEM_PROMPT, request.question(),
                    request.temperature(), request.topP());
            long duration = System.currentTimeMillis() - start;
            return new QueryResponse(answer, List.of(), duration,
                    false, false, false, 0, null,
                    false, false, false, "DIRECT",
                    false, false, false, false);
        }

        // ── 1. Adaptive RAG : routing ──────────────────────────────────────
        String ragStrategy = "STANDARD";
        boolean forceAgentic = false;

        if (RagOverrides.resolve(ov.adaptive(), adaptiveRagService.isPresent())) {
            AdaptiveRagService.RagStrategy strategy = adaptiveRagService.get().classifyQuery(request.question());
            ragStrategy = strategy.name();

            if (strategy == AdaptiveRagService.RagStrategy.DIRECT) {
                String answer = llmClient.chat(DIRECT_SYSTEM_PROMPT, request.question(),
                        request.temperature(), request.topP());
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

        if (RagOverrides.resolve(ov.conversational(), conversationalRagService.isPresent())
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
        RagContext ctx = retrieveContext(request, retrievalQuestion, ov);

        // ── 4. Corrective RAG : filtrage des chunks non pertinents ─────────
        //       (+ retrieval complémentaire si trop peu de chunks pertinents)
        boolean correctiveApplied = false;

        if (RagOverrides.resolve(ov.corrective(), correctiveRagService.isPresent()) && !ctx.contextChunks().isEmpty()) {
            boolean useHybrid = RagOverrides.resolve(ov.hybrid(), hybridSearchService.isPresent());
            CorrectiveOutcome outcome = applyCorrectiveRag(request, retrievalQuestion, ctx, useHybrid);
            ctx = outcome.ctx();
            correctiveApplied = outcome.applied();
        }

        // ── 4.5. Context Compression : extraction des passages pertinents ──
        boolean compressionApplied = false;

        if (RagOverrides.resolve(ov.compression(), contextCompressionService.isPresent()) && !ctx.contextChunks().isEmpty()) {
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

        // ── 6. Génération (standard ou Self-RAG) ───────────────────────────
        String answer;
        boolean selfRagApplied = false;

        if (ctx.contextChunks().isEmpty()) {
            answer = "Aucun document pertinent trouvé dans la base de connaissances. "
                    + "Veuillez d'abord ingérer des documents via POST /api/ingest.";
        } else {
            String systemPrompt = ctx.systemPrompt();
            String userMessage  = buildUserMessage(request, conversationalApplied);

            if (RagOverrides.resolve(ov.selfRag(), selfRagService.isPresent())) {
                SelfRagService.SelfRagResult result = selfRagService.get()
                        .reflect(request.question(), ctx.contextChunks(), systemPrompt, userMessage,
                                request.temperature(), request.topP());
                answer = result.answer();
                selfRagApplied = result.reflectionApplied();
            } else {
                answer = llmClient.chat(systemPrompt, userMessage,
                        request.temperature(), request.topP());
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

    /**
     * Métadonnées du pipeline émises dans l'événement SSE {@code done}.
     * Sérialisées via Jackson ; les champs {@code null} sont omis.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record PipelineMeta(
            boolean conversationalApplied,
            boolean correctiveApplied,
            boolean selfRagApplied,
            String ragStrategy,
            boolean rerankApplied,
            boolean hybridSearchApplied,
            boolean multiQueryApplied,
            boolean semanticDedupApplied,
            boolean longContextApplied,
            boolean compressionApplied,
            int chunkCount,
            /** Question autonome utilisée pour le retrieval (Conversational RAG), {@code null} sinon. */
            String rewrittenQuestion,
            int agenticIterations,
            String agenticStopReason,
            /** Scores de réflexion Self-RAG (ISREL/ISSUP/ISUSE), {@code null} si non évalué. */
            String selfRagScores,
            /** Chronologie des étapes du pipeline (durée serveur + compteurs), pour la timeline du Trace. */
            List<StageTrace> stages
    ) {
        /** Variante sans timeline (chemins directs). */
        PipelineMeta(boolean conversationalApplied, boolean correctiveApplied, boolean selfRagApplied,
                     String ragStrategy, boolean rerankApplied, boolean hybridSearchApplied,
                     boolean multiQueryApplied, boolean semanticDedupApplied, boolean longContextApplied,
                     boolean compressionApplied, int chunkCount, String rewrittenQuestion,
                     int agenticIterations, String agenticStopReason, String selfRagScores) {
            this(conversationalApplied, correctiveApplied, selfRagApplied, ragStrategy, rerankApplied,
                    hybridSearchApplied, multiQueryApplied, semanticDedupApplied, longContextApplied,
                    compressionApplied, chunkCount, rewrittenQuestion, agenticIterations, agenticStopReason,
                    selfRagScores, null);
        }
    }

    /**
     * Une étape du pipeline mesurée côté serveur pour la timeline du panneau Trace.
     *
     * @param stage      identifiant de l'étape (routing, retrieval, grading, compression, agentic, generation…)
     * @param durationMs durée serveur de l'étape (isole le temps par phase, sans jitter réseau)
     * @param inCount    cardinalité en entrée ({@code null} si non pertinent) — ex. chunks avant filtrage
     * @param outCount   cardinalité en sortie ({@code null} si non pertinent) — ex. chunks après filtrage
     * @param detail     précision optionnelle (stratégie retenue, scores…)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record StageTrace(String stage, long durationMs, Integer inCount, Integer outCount, String detail) {}

    /**
     * Streaming SSE token par token. Émet : {@code stage}* → {@code sources} →
     * ({@code replace}? {@code token}*)* → {@code done} | {@code error}.
     *
     * <p>Tout le pipeline non-streaming est désormais porté : Adaptive RAG (routage
     * DIRECT/STANDARD/AGENTIC), Conversational, Corrective, Compression, Agentic (boucle
     * ReAct, réponse émise en un bloc à la fin) et Self-RAG (le brouillon est streamé
     * normalement, puis auto-évalué ; s'il est raffiné, un événement {@code replace}
     * demande au client d'effacer le brouillon avant de streamer la version raffinée).</p>
     *
     * <p>Les événements {@code stage} tracent l'étape en cours (routing, retrieval,
     * grading, agentic_search…) : ils donnent au client la visibilité sur le pipeline et
     * servent de keep-alive pendant les étapes longues (boucle agentique sur CPU).</p>
     *
     * <p>L'événement {@code done} contient un JSON {@link PipelineMeta}.</p>
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
                        ServerSentEvent<String> doneEvent = doneEvent(new PipelineMeta(
                                false, false, false, "DIRECT", false, false, false, false, false, false,
                                0, null, 0, null, null));
                        return Flux.concat(Flux.just(sourcesEvent), tokens, Flux.just(doneEvent));
                    })
                    .onErrorResume(e -> {
                        log.error("Erreur streaming direct: {}", e.getMessage());
                        return Flux.just(errorEvent(e));
                    });
        }

        // Pipeline RAG complet : émetteur bloquant sur boundedElastic. Flux.create permet
        // d'émettre des événements au fil du pipeline (stages, boucle agentique, passes
        // Self-RAG) là où l'ancien Mono.fromCallable ne pouvait rien émettre avant la fin
        // du setup — le client restait silencieux pendant tout le retrieval.
        return Flux.<ServerSentEvent<String>>create(sink -> {
                    try {
                        runStreamPipeline(request, sink);
                    } catch (Exception e) {
                        log.error("Erreur streaming RAG: {}", e.getMessage());
                        if (!sink.isCancelled()) sink.next(errorEvent(e));
                    } finally {
                        sink.complete();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Corps du pipeline RAG streaming — exécuté de façon bloquante sur boundedElastic. */
    private void runStreamPipeline(QueryRequest request, FluxSink<ServerSentEvent<String>> sink) {
        // Surcharges par requête (toggles Playground / comparaison A/B) : chaque module
        // optionnel est résolu via RagOverrides.resolve, exactement comme le chemin non-streaming.
        RagOverrides ov = request.overrides() != null ? request.overrides() : RagOverrides.NONE;
        // Timeline serveur : durée + compteurs par étape, remontés dans l'événement done.
        List<StageTrace> trace = new ArrayList<>();

        // ── 1. Adaptive RAG : routage ──────────────────────────────────────
        String ragStrategy = "STANDARD";
        boolean forceAgentic = false;
        if (RagOverrides.resolve(ov.adaptive(), adaptiveRagService.isPresent())) {
            long t0 = System.currentTimeMillis();
            emitStage(sink, "routing", null, null);
            AdaptiveRagService.RagStrategy strategy = adaptiveRagService.get().classifyQuery(request.question());
            ragStrategy = strategy.name();
            trace.add(new StageTrace("routing", System.currentTimeMillis() - t0, null, null, strategy.name()));

            if (strategy == AdaptiveRagService.RagStrategy.DIRECT) {
                sink.next(ServerSentEvent.<String>builder().event("sources").data("[]").build());
                long tg = System.currentTimeMillis();
                forwardTokens(sink, llmClient.chatStream(DIRECT_SYSTEM_PROMPT, request.question(),
                        request.temperature(), request.topP()));
                trace.add(new StageTrace("generation", System.currentTimeMillis() - tg, null, null, null));
                sink.next(doneEvent(new PipelineMeta(false, false, false, "DIRECT",
                        false, false, false, false, false, false, 0, null, 0, null, null, trace)));
                return;
            }
            // Comme en non-streaming : AGENTIC sans service agentique → dégradation STANDARD
            // (la stratégie affichée reste AGENTIC, cf. query()).
            forceAgentic = strategy == AdaptiveRagService.RagStrategy.AGENTIC && agenticRagService.isPresent();
        }

        // ── 2. Conversational RAG : contextualisation de la question ──────
        String retrievalQuestion = request.question();
        boolean conversationalApplied = false;
        if (RagOverrides.resolve(ov.conversational(), conversationalRagService.isPresent())
                && request.conversationHistory() != null
                && !request.conversationHistory().isEmpty()) {
            long t0 = System.currentTimeMillis();
            emitStage(sink, "rewriting", null, null);
            String standalone = conversationalRagService.get()
                    .contextualizeQuestion(request.question(), request.conversationHistory());
            if (!standalone.equals(request.question())) {
                retrievalQuestion = standalone;
                conversationalApplied = true;
            }
            trace.add(new StageTrace("rewriting", System.currentTimeMillis() - t0, null, null,
                    conversationalApplied ? "rephrased" : "unchanged"));
        }
        String rewrittenQuestion = conversationalApplied ? retrievalQuestion : null;

        // ── 3. Retrieval ───────────────────────────────────────────────────
        long tRetrieval = System.currentTimeMillis();
        emitStage(sink, "retrieval", null, null);
        RagContext ctx = retrieveContext(request, retrievalQuestion, ov);
        trace.add(new StageTrace("retrieval", System.currentTimeMillis() - tRetrieval,
                null, ctx.contextChunks().size(), null));

        // ── 4. Corrective RAG ──────────────────────────────────────────────
        boolean correctiveApplied = false;
        if (RagOverrides.resolve(ov.corrective(), correctiveRagService.isPresent()) && !ctx.contextChunks().isEmpty()) {
            long t0 = System.currentTimeMillis();
            int before = ctx.contextChunks().size();
            emitStage(sink, "grading", null, null);
            boolean useHybrid = RagOverrides.resolve(ov.hybrid(), hybridSearchService.isPresent());
            CorrectiveOutcome outcome = applyCorrectiveRag(request, retrievalQuestion, ctx, useHybrid);
            ctx = outcome.ctx();
            correctiveApplied = outcome.applied();
            trace.add(new StageTrace("grading", System.currentTimeMillis() - t0,
                    before, ctx.contextChunks().size(), null));
        }

        // ── 4.5. Context Compression ───────────────────────────────────────
        boolean compressionApplied = false;
        if (RagOverrides.resolve(ov.compression(), contextCompressionService.isPresent()) && !ctx.contextChunks().isEmpty()) {
            long t0 = System.currentTimeMillis();
            int before = ctx.contextChunks().size();
            emitStage(sink, "compression", null, null);
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
            trace.add(new StageTrace("compression", System.currentTimeMillis() - t0,
                    before, ctx.contextChunks().size(), null));
        }

        // ── 5. Agentic RAG : boucle ReAct ──────────────────────────────────
        // La réponse est produite PAR la boucle (dernier appel ReAct) : elle ne peut pas
        // être streamée token par token — elle est émise en un bloc à la fin. Les stages
        // agentic_search (une par itération, avec la requête reformulée) donnent la
        // visibilité sur le raisonnement et maintiennent la connexion active.
        if (forceAgentic) {
            long t0 = System.currentTimeMillis();
            QueryResponse resp = agenticRagService.get().query(
                    request, ctx.contextChunks(), ctx.chunkMetadatas(), ctx.chunkDistances(),
                    ctx.rerankApplied(), ctx.hybridApplied(),
                    (iteration, query) -> emitStage(sink, "agentic_search", iteration, query));
            trace.add(new StageTrace("agentic", System.currentTimeMillis() - t0,
                    null, resp.agenticIterations(),
                    resp.agenticStopReason() != null ? resp.agenticStopReason().name() : null));
            sink.next(sourcesEvent(resp.sources()));
            if (resp.answer() != null && !resp.answer().isEmpty() && !sink.isCancelled()) {
                sink.next(ServerSentEvent.<String>builder().event("token").data(resp.answer()).build());
            }
            sink.next(doneEvent(new PipelineMeta(
                    conversationalApplied, correctiveApplied, false, "AGENTIC",
                    resp.rerankApplied(), resp.hybridSearchApplied(),
                    ctx.multiQueryApplied(), ctx.semanticDedupApplied(), ctx.longContextApplied(),
                    compressionApplied, resp.sources().size(), rewrittenQuestion,
                    resp.agenticIterations(),
                    resp.agenticStopReason() != null ? resp.agenticStopReason().name() : null,
                    null, trace)));
            return;
        }

        // ── 6. Sources + génération (standard ou Self-RAG) ─────────────────
        sink.next(sourcesEvent(ctx.sources()));

        if (ctx.contextChunks().isEmpty()) {
            sink.next(ServerSentEvent.<String>builder().event("token")
                    .data("Aucun document pertinent trouvé dans la base de connaissances.").build());
            sink.next(doneEvent(new PipelineMeta(
                    conversationalApplied, correctiveApplied, false, ragStrategy,
                    ctx.rerankApplied(), ctx.hybridApplied(), ctx.multiQueryApplied(),
                    ctx.semanticDedupApplied(), ctx.longContextApplied(), compressionApplied,
                    0, rewrittenQuestion, 0, null, null, trace)));
            return;
        }

        String userMessage = buildUserMessage(request, conversationalApplied);
        boolean selfRagApplied = false;
        String selfRagScores = null;
        long tGen = System.currentTimeMillis();

        if (RagOverrides.resolve(ov.selfRag(), selfRagService.isPresent())) {
            // Self-RAG streaming : le brouillon est streamé normalement (TTFT préservé)
            // puis auto-évalué. Si un raffinement s'impose, l'événement replace demande
            // au client d'effacer le brouillon avant de streamer la version raffinée.
            SelfRagService selfRag = selfRagService.get();
            String draft = forwardTokens(sink, llmClient.chatStream(ctx.systemPrompt(), userMessage,
                    request.temperature(), request.topP()));
            if (sink.isCancelled()) return;
            trace.add(new StageTrace("generation", System.currentTimeMillis() - tGen, null, null, null));

            long tReflect = System.currentTimeMillis();
            emitStage(sink, "reflection", null, null);
            SelfRagService.ReflectionScores scores = selfRag.evaluate(request.question(), ctx.contextChunks(), draft);
            selfRagScores = formatScores(scores);

            if (selfRag.requiresRefinement(scores) && selfRag.maxReflectionIterations() > 0) {
                sink.next(ServerSentEvent.<String>builder().event("replace").data("{}").build());
                emitStage(sink, "refining", null, null);
                String refined = forwardTokens(sink, llmClient.chatStream(
                        selfRag.refineSystemPrompt(ctx.systemPrompt()), userMessage,
                        request.temperature(), request.topP()));
                if (!sink.isCancelled()) {
                    selfRagScores = formatScores(selfRag.evaluate(request.question(), ctx.contextChunks(), refined));
                }
                selfRagApplied = true;
            }
            trace.add(new StageTrace("reflection", System.currentTimeMillis() - tReflect, null, null, selfRagScores));
        } else {
            forwardTokens(sink, llmClient.chatStream(ctx.systemPrompt(), userMessage,
                    request.temperature(), request.topP()));
            trace.add(new StageTrace("generation", System.currentTimeMillis() - tGen, null, null, null));
        }

        sink.next(doneEvent(new PipelineMeta(
                conversationalApplied, correctiveApplied, selfRagApplied, ragStrategy,
                ctx.rerankApplied(), ctx.hybridApplied(), ctx.multiQueryApplied(),
                ctx.semanticDedupApplied(), ctx.longContextApplied(), compressionApplied,
                ctx.contextChunks().size(), rewrittenQuestion, 0, null, selfRagScores, trace)));
    }

    // ── Helpers SSE ────────────────────────────────────────────────────────────

    /**
     * Consomme un flux de tokens de façon bloquante en les relayant au sink SSE, et
     * retourne la réponse complète concaténée. La déconnexion du client (cancel) est
     * détectée par polling et dispose la génération LLM ; timeout d'inactivité
     * ({@code streamTimeout}) et erreurs LLM sont propagés à l'appelant.
     */
    private String forwardTokens(FluxSink<ServerSentEvent<String>> sink, Flux<String> tokens) {
        StringBuilder full = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Disposable subscription = tokens.timeout(streamTimeout).subscribe(
                t -> {
                    full.append(t);
                    if (!sink.isCancelled()) {
                        sink.next(ServerSentEvent.<String>builder().event("token").data(t).build());
                    }
                },
                e -> { failure.set(e); latch.countDown(); },
                latch::countDown);
        try {
            while (!latch.await(500, TimeUnit.MILLISECONDS)) {
                if (sink.isCancelled()) {
                    subscription.dispose();
                    return full.toString();
                }
            }
        } catch (InterruptedException ie) {
            subscription.dispose();
            Thread.currentThread().interrupt();
            return full.toString();
        }
        Throwable e = failure.get();
        if (e != null) throw Exceptions.propagate(e);
        return full.toString();
    }

    /**
     * Émet un événement SSE {@code stage} décrivant l'étape de pipeline en cours —
     * visibilité côté client et keep-alive pendant les étapes longues.
     */
    private void emitStage(FluxSink<ServerSentEvent<String>> sink, String stage,
                           Integer iteration, String query) {
        if (sink.isCancelled()) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", stage);
        if (iteration != null) payload.put("iteration", iteration);
        if (query != null) payload.put("query", query);
        try {
            sink.next(ServerSentEvent.<String>builder().event("stage")
                    .data(objectMapper.writeValueAsString(payload)).build());
        } catch (JsonProcessingException e) {
            // étape purement informative — jamais bloquante
        }
    }

    private ServerSentEvent<String> sourcesEvent(List<QueryResponse.Source> sources) {
        try {
            return ServerSentEvent.<String>builder().event("sources")
                    .data(objectMapper.writeValueAsString(sources)).build();
        } catch (JsonProcessingException e) {
            return ServerSentEvent.<String>builder().event("sources").data("[]").build();
        }
    }

    /** Formate les scores de réflexion Self-RAG pour l'événement {@code done}. */
    private static String formatScores(SelfRagService.ReflectionScores scores) {
        return scores.isRel() + "/" + scores.isSup() + "/" + scores.isUse();
    }

    /**
     * Construit l'événement SSE {@code done} contenant les métadonnées du pipeline,
     * sérialisées via Jackson (l'assemblage manuel de JSON cassait le parsing côté
     * client dès qu'une valeur contenait un caractère à échapper).
     */
    private ServerSentEvent<String> doneEvent(PipelineMeta meta) {
        String json;
        try {
            json = objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            json = "{\"ragStrategy\":\"" + meta.ragStrategy() + "\"}";
        }
        return ServerSentEvent.<String>builder().event("done").data(json).build();
    }

    /** Construit l'événement SSE {@code error} avec un message JSON toujours valide. */
    private ServerSentEvent<String> errorEvent(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : "Erreur interne";
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of("message", msg));
        } catch (JsonProcessingException ex) {
            json = "{\"message\":\"Erreur interne\"}";
        }
        return ServerSentEvent.<String>builder().event("error").data(json).build();
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
        return retrieveContext(request, retrievalQuestion, RagOverrides.NONE);
    }

    /** Retrieval avec surcharges par requête (cf. {@link #query(QueryRequest, RagOverrides)}). */
    public RagContext retrieveContext(QueryRequest request, String retrievalQuestion, RagOverrides overrides) {
        RagOverrides ov = overrides != null ? overrides : RagOverrides.NONE;
        boolean useReranker   = RagOverrides.resolve(ov.rerank(), rerankerClient.isPresent());
        boolean useHybrid     = RagOverrides.resolve(ov.hybrid(), hybridSearchService.isPresent());
        boolean useMultiQuery = RagOverrides.resolve(ov.multiQuery(), multiQueryService.isPresent());

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
                    RagContext fullContext = buildFullContextResult(collectionId);
                    // null = corpus au-delà du budget de tokens → retrieval standard
                    if (fullContext != null) {
                        log.info("Long-context RAG : {} chunks ≤ {} → chargement intégral sans retrieval vectoriel",
                                collectionSize, maxChunks);
                        return fullContext;
                    }
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

        if (useMultiQuery) {
            try {
                List<String> queries = multiQueryService.get().generateQueries(retrievalQuestion);
                if (queries.size() > 1) {
                    MultiQueryMerge merged = executeMultiQueryRetrieval(
                            queries, collectionId, collectionName, retrieveCount, useHybrid);
                    allChunks      = merged.chunks();
                    allMetadatas   = merged.metadatas();
                    allDistances   = merged.distances();
                    allBm25Scores  = merged.bm25Scores();
                    hybridApplied  = merged.hybridApplied();
                    multiQueryApplied = true;
                } else {
                    SingleQueryResult r = executeSingleQuery(retrievalQuestion, collectionId, collectionName, retrieveCount, useHybrid);
                    allChunks = r.chunks(); allMetadatas = r.metadatas();
                    allDistances = r.distances(); allBm25Scores = r.bm25Scores();
                    hybridApplied = r.hybridApplied();
                }
            } catch (Exception e) {
                log.warn("Multi-query échoué, fallback retrieval simple: {}", e.getMessage());
                SingleQueryResult r = executeSingleQuery(retrievalQuestion, collectionId, collectionName, retrieveCount, useHybrid);
                allChunks = r.chunks(); allMetadatas = r.metadatas();
                allDistances = r.distances(); allBm25Scores = r.bm25Scores();
                hybridApplied = r.hybridApplied();
            }
        } else {
            SingleQueryResult r = executeSingleQuery(retrievalQuestion, collectionId, collectionName, retrieveCount, useHybrid);
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

    /** Résultat de l'étape Corrective RAG : contexte éventuellement filtré/complété. */
    private record CorrectiveOutcome(RagContext ctx, boolean applied) {}

    /**
     * Applique le Corrective RAG : grading LLM des chunks, filtrage des non-pertinents, et —
     * si le nombre de chunks conservés tombe sous {@code spectra.corrective-rag.min-relevant-chunks}
     * — reformulation de la question puis retrieval complémentaire (un seul essai), dont les
     * nouveaux chunks sont gradés à leur tour avant d'être ajoutés au contexte.
     *
     * <p>Partagé entre {@link #query(QueryRequest, RagOverrides)} et {@link #queryStream}.</p>
     *
     * @param retrievalQuestion question utilisée pour le retrieval (éventuellement reformulée
     *                          par le Conversational RAG) — sert de base à la reformulation
     */
    private CorrectiveOutcome applyCorrectiveRag(QueryRequest request, String retrievalQuestion,
                                                 RagContext ctx, boolean useHybrid) {
        CorrectiveRagService corrective = correctiveRagService.get();
        List<Integer> keptIndices = corrective.gradeChunks(request.question(), ctx.contextChunks());

        boolean applied = false;
        RagContext result = ctx;

        if (keptIndices.size() < ctx.contextChunks().size()) {
            CorrectiveRagService.FilteredContext filtered = corrective.filterByIndices(
                    keptIndices,
                    ctx.contextChunks(), ctx.chunkMetadatas(), ctx.chunkDistances(),
                    ctx.rerankScores(), ctx.bm25Scores());
            result = rebuildContext(filtered, ctx.rerankApplied(), ctx.hybridApplied(),
                    ctx.multiQueryApplied(), ctx.semanticDedupApplied(), ctx.longContextApplied());
            applied = true;
        }

        if (keptIndices.size() < corrective.minRelevantChunks()) {
            RagContext completed = complementaryRetrieval(request, retrievalQuestion, result, corrective, useHybrid);
            if (completed != null) {
                result = completed;
                applied = true;
            }
        }
        return new CorrectiveOutcome(result, applied);
    }

    /**
     * Retrieval complémentaire du Corrective RAG : reformule la question, exécute un retrieval
     * simple (vectoriel ou hybride, sans multi-query ni re-ranking pour borner la latence),
     * grade les chunks inédits et ajoute les pertinents au contexte, borné à
     * {@code maxContextChunks}.
     *
     * @return le contexte complété, ou {@code null} si rien n'a pu être ajouté (reformulation
     *         impossible, aucun chunk nouveau, aucun jugé pertinent, ou erreur — dégradation
     *         gracieuse : le contexte courant est conservé)
     */
    private RagContext complementaryRetrieval(QueryRequest request, String retrievalQuestion,
                                              RagContext current, CorrectiveRagService corrective,
                                              boolean useHybrid) {
        Optional<String> reformulated = corrective.reformulateQuery(retrievalQuestion);
        if (reformulated.isEmpty()) return null;

        try {
            String collectionName = request.collection() != null ? request.collection()
                    : (props.chromadb() != null ? props.chromadb().effectiveCollection() : COLLECTION_NAME);
            String collectionId = chromaDbClient.getOrCreateCollection(collectionName);
            SingleQueryResult extra = executeSingleQuery(reformulated.get(), collectionId, collectionName,
                    request.maxContextChunks(), useHybrid);

            // Ne considérer que les chunks pas déjà présents dans le contexte courant.
            Set<String> seen = new HashSet<>(current.contextChunks());
            List<Integer> newIdx = new ArrayList<>();
            for (int i = 0; i < extra.chunks().size(); i++) {
                if (seen.add(extra.chunks().get(i))) newIdx.add(i);
            }
            if (newIdx.isEmpty()) {
                log.info("Corrective RAG : retrieval complémentaire sans chunk nouveau");
                return null;
            }

            List<String> newChunks = filterByIndices(extra.chunks(), newIdx);
            List<Integer> gradedKept = corrective.gradeChunks(request.question(), newChunks);
            if (gradedKept.isEmpty()) {
                log.info("Corrective RAG : aucun chunk complémentaire jugé pertinent");
                return null;
            }

            // Fusion : contexte conservé + nouveaux chunks pertinents (non re-rankés → score null).
            List<String>              chunks = new ArrayList<>(current.contextChunks());
            List<Map<String, String>> metas  = new ArrayList<>(current.chunkMetadatas());
            List<Double>              dists  = new ArrayList<>(current.chunkDistances());
            List<Float> rerank = current.rerankScores() != null ? new ArrayList<>(current.rerankScores()) : null;
            List<Float> bm25   = current.bm25Scores()   != null ? new ArrayList<>(current.bm25Scores())   : null;

            int before = chunks.size();
            for (int k : gradedKept) {
                if (chunks.size() >= request.maxContextChunks()) break;
                int i = newIdx.get(k);
                chunks.add(extra.chunks().get(i));
                metas.add(extra.metadatas().get(i));
                dists.add(extra.distances().get(i));
                if (rerank != null) rerank.add(null);
                if (bm25   != null) bm25.add(extra.bm25Scores() != null ? extra.bm25Scores().get(i) : null);
            }
            if (chunks.size() == before) return null;

            log.info("Corrective RAG : retrieval complémentaire — {} chunk(s) ajouté(s) au contexte",
                    chunks.size() - before);
            return buildRagContext(chunks, metas, dists, rerank, bm25,
                    current.rerankApplied(), current.hybridApplied() || extra.hybridApplied(),
                    current.multiQueryApplied(), current.semanticDedupApplied(), current.longContextApplied());
        } catch (Exception e) {
            log.warn("Corrective RAG : retrieval complémentaire échoué — {}", e.getMessage());
            return null;
        }
    }

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
                                                  String collectionName, int retrieveCount, boolean useHybrid) {
        List<Float> embedding = embeddingService.embed(question);

        if (useHybrid) {
            List<HybridSearchService.HybridChunk> results =
                    hybridSearchService.get().search(question, embedding, collectionId, collectionName, retrieveCount);
            List<String>              chunks    = new ArrayList<>(results.size());
            List<Map<String, String>> metadatas = new ArrayList<>(results.size());
            List<Double>              distances = new ArrayList<>(results.size());
            List<Float>               bm25      = new ArrayList<>(results.size());
            List<Double>              rrf       = new ArrayList<>(results.size());
            for (HybridSearchService.HybridChunk hc : results) {
                chunks.add(hc.text());
                metadatas.add(Map.of("sourceFile", hc.sourceFile()));
                distances.add(hc.vectorDistance());
                bm25.add(hc.bm25Score());
                rrf.add(hc.rrfScore());
            }
            return new SingleQueryResult(chunks, metadatas, distances, bm25, rrf, !results.isEmpty());
        } else {
            Map<String, Object> results = chromaDbClient.query(collectionId, embedding, retrieveCount);
            List<List<String>>              documents = (List<List<String>>) results.get("documents");
            List<List<Map<String, String>>> metadatas = (List<List<Map<String, String>>>) results.get("metadatas");
            List<List<Double>>              distances = (List<List<Double>>) results.get("distances");
            return new SingleQueryResult(
                    (documents == null || documents.isEmpty()) ? List.of() : documents.getFirst(),
                    (metadatas == null || metadatas.isEmpty()) ? List.of() : metadatas.getFirst(),
                    (distances == null || distances.isEmpty()) ? List.of() : distances.getFirst(),
                    null, null, false);
        }
    }

    /**
     * Exécute le retrieval pour chaque requête de la liste, fusionne les résultats
     * en déduplication exacte sur le texte du chunk.
     *
     * <p><b>Classement de la fusion.</b> En recherche hybride, chaque résultat porte un score
     * RRF ; les scores d'un même chunk sont SOMMÉS entre les variantes (fusion RRF standard :
     * un chunk retrouvé par plusieurs reformulations est renforcé) et le tri final se fait par
     * score RRF décroissant. Trier par distance vectorielle écraserait le classement hybride :
     * les chunks issus du BM25 seul portent une distance sentinelle (1.0) qui les reléguait
     * systématiquement en queue. En vectoriel pur (pas de RRF), tri par distance croissante.</p>
     *
     * <p>Le résultat est limité à {@code retrieveCount} chunks.</p>
     */
    private MultiQueryMerge executeMultiQueryRetrieval(List<String> queries, String collectionId,
                                                        String collectionName, int retrieveCount, boolean useHybrid) {
        // LinkedHashMap pour conserver l'ordre d'insertion (question originale en premier)
        Map<String, Integer> indexByText = new LinkedHashMap<>();
        List<String>              mergedChunks    = new ArrayList<>();
        List<Map<String, String>> mergedMetadatas = new ArrayList<>();
        List<Double>              mergedDistances = new ArrayList<>();
        List<Float>               mergedBm25      = new ArrayList<>();
        List<Double>              mergedRrf       = new ArrayList<>();
        boolean hybridApplied = false;
        boolean trackBm25 = true; // désactivé si un résultat n'a pas de scores BM25
        boolean trackRrf  = true; // désactivé si un résultat n'a pas de scores RRF (vectoriel pur)

        for (String query : queries) {
            SingleQueryResult r = executeSingleQuery(query, collectionId, collectionName, retrieveCount, useHybrid);
            hybridApplied = hybridApplied || r.hybridApplied();
            if (r.bm25Scores() == null) trackBm25 = false;
            if (r.rrfScores()  == null) trackRrf  = false;

            for (int i = 0; i < r.chunks().size(); i++) {
                String text = r.chunks().get(i);
                if (!indexByText.containsKey(text)) {
                    indexByText.put(text, mergedChunks.size());
                    mergedChunks.add(text);
                    mergedMetadatas.add(r.metadatas().get(i));
                    mergedDistances.add(r.distances().get(i));
                    if (trackBm25 && r.bm25Scores() != null) mergedBm25.add(r.bm25Scores().get(i));
                    if (trackRrf  && r.rrfScores()  != null) mergedRrf.add(r.rrfScores().get(i));
                } else {
                    int existingIdx = indexByText.get(text);
                    // Conserve la meilleure distance (plus faible = plus proche) pour ce chunk
                    if (r.distances().get(i) < mergedDistances.get(existingIdx)) {
                        mergedDistances.set(existingIdx, r.distances().get(i));
                    }
                    // Somme les contributions RRF des différentes variantes (fusion RRF standard)
                    if (trackRrf && r.rrfScores() != null && existingIdx < mergedRrf.size()) {
                        mergedRrf.set(existingIdx, mergedRrf.get(existingIdx) + r.rrfScores().get(i));
                    }
                }
            }
        }

        // Tri : score RRF décroissant si disponible (hybride), sinon distance croissante —
        // puis limite à retrieveCount.
        boolean sortByRrf = trackRrf && mergedRrf.size() == mergedChunks.size() && !mergedRrf.isEmpty();
        List<Integer> sortedIdx = new ArrayList<>();
        for (int i = 0; i < mergedChunks.size(); i++) sortedIdx.add(i);
        if (sortByRrf) {
            sortedIdx.sort((a, b) -> Double.compare(mergedRrf.get(b), mergedRrf.get(a)));
        } else {
            sortedIdx.sort((a, b) -> Double.compare(mergedDistances.get(a), mergedDistances.get(b)));
        }
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

    /** Heuristique d'estimation de tokens (identique à AgenticRagService) : 1 token ≈ 4 caractères. */
    private static final int CHARS_PER_TOKEN = 4;

    /**
     * Charge tous les documents d'une collection pour le Long-Context RAG bypass.
     *
     * <p>Retourne {@code null} si le corpus dépasse le budget
     * {@code spectra.long-context-rag.max-context-tokens} : injecter un corpus qui déborde
     * de la fenêtre du modèle tronquerait silencieusement le prompt, et un préfixe
     * arbitraire du corpus est moins pertinent qu'un retrieval vectoriel ciblé — l'appelant
     * retombe alors sur le retrieval standard.</p>
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

        int maxContextTokens = props.longContextRag().effectiveMaxContextTokens();
        long totalChars = 0;
        for (String doc : documents) {
            if (doc != null) totalChars += doc.length();
        }
        long estimatedTokens = totalChars / CHARS_PER_TOKEN;
        if (estimatedTokens > maxContextTokens) {
            log.info("Long-context RAG : corpus estimé à ~{} tokens > budget {} → fallback retrieval standard",
                    estimatedTokens, maxContextTokens);
            return null;
        }

        // Distance 0.0 : tous les chunks sont directement pertinents (pas de filtrage vectoriel)
        List<Double> distances = new ArrayList<>(Collections.nCopies(documents.size(), 0.0));
        // Métadonnées alignées sur documents : une réponse Chroma sans metadatas (ou avec des
        // entrées nulles) provoquait un IndexOutOfBounds/NPE dans buildRagContext.
        List<Map<String, String>> safeMetadatas = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            Map<String, String> m = (metadatas != null && i < metadatas.size()) ? metadatas.get(i) : null;
            safeMetadatas.add(m != null ? m : Map.of());
        }
        return buildRagContext(documents, safeMetadatas, distances,
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
