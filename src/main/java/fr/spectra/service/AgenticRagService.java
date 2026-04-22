package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.QueryRequest;
import fr.spectra.dto.QueryResponse;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agentic RAG — boucle de raisonnement ReAct (I4).
 *
 * <p>Le LLM décide à chaque itération s'il a besoin de rechercher plus d'information
 * ou s'il est prêt à répondre, en suivant le schéma THOUGHT / ACTION / QUERY|RESPONSE.
 * La boucle s'arrête dès que le modèle émet {@code ACTION: ANSWER}, ou au bout de
 * {@code spectra.agentic-rag.max-iterations} tours (défaut : 3).</p>
 *
 * <p>Activé uniquement si {@code SPECTRA_AGENTIC_RAG_ENABLED=true}.
 * Compatible avec les modules I1 (re-ranking) et I2 (hybrid search).</p>
 */
@Service
@ConditionalOnProperty(prefix = "spectra.agentic-rag", name = "enabled", havingValue = "true")
public class AgenticRagService {

    private static final Logger log = LoggerFactory.getLogger(AgenticRagService.class);

    // ---------- Prompt ReAct -----------------------------------------------

    private static final String REACT_SYSTEM_PROMPT_TEMPLATE = """
            Tu es un assistant de recherche documentaire. Tu raisonnes étape par étape \
            avant de répondre grâce à une boucle THOUGHT → ACTION.

            À chaque tour, réponds UNIQUEMENT dans l'un de ces deux formats :

            ── Format RECHERCHE (si tu as besoin de plus d'information) ──
            THOUGHT: <ton raisonnement>
            ACTION: SEARCH
            QUERY: <requête de recherche précise, différente de la précédente>

            ── Format RÉPONSE (si tu as suffisamment d'information) ──
            THOUGHT: <ton raisonnement final>
            ACTION: ANSWER
            RESPONSE: <ta réponse complète et précise>

            Règles absolues :
            - Base-toi UNIQUEMENT sur le contexte fourni.
            - Si le contexte est insuffisant même après recherche, dis-le clairement dans RESPONSE.
            - N'invente aucune information. %s
            """;

    private static final String FALLBACK_SYSTEM_PROMPT_TEMPLATE = """
            Tu es un assistant spécialisé. Réponds de manière précise et concise \
            en te basant UNIQUEMENT sur le contexte fourni.
            Si le contexte ne contient pas l'information demandée, dis-le clairement.
            Ne fabrique pas d'information. %s

            === CONTEXTE ===
            %%s
            === FIN DU CONTEXTE ===""";

    /** Tokens de contexte estimés via heuristique (1 token ≈ 4 caractères). */
    private static final int CHARS_PER_TOKEN = 4;
    /** Tokens réservés pour la réponse du LLM (prompt + tokens d'amorçage). */
    private static final int RESPONSE_RESERVE_TOKENS = 500;

    // ---------- Regex parseurs ---------------------------------------------

    private static final Pattern ACTION_PATTERN =
            Pattern.compile("ACTION:\\s*(SEARCH|ANSWER)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY_PATTERN =
            Pattern.compile("QUERY:\\s*(.+?)(?:\\n|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESPONSE_PATTERN =
            Pattern.compile("RESPONSE:\\s*(.+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // ---------- Dépendances ------------------------------------------------

    private final ChromaDbClient chromaDbClient;
    private final EmbeddingService embeddingService;
    private final LlmChatClient llmClient;
    private final Optional<RerankerClient> rerankerClient;
    private final Optional<HybridSearchService> hybridSearchService;
    private final SpectraProperties props;

    // Micrometer metrics
    private final MeterRegistry meterRegistry;
    private final DistributionSummary iterationsSummary;
    private final Timer durationTimer;

    public AgenticRagService(ChromaDbClient chromaDbClient,
                              EmbeddingService embeddingService,
                              LlmChatClient llmClient,
                              Optional<RerankerClient> rerankerClient,
                              Optional<HybridSearchService> hybridSearchService,
                              SpectraProperties props,
                              MeterRegistry meterRegistry) {
        this.chromaDbClient = chromaDbClient;
        this.embeddingService = embeddingService;
        this.llmClient = llmClient;
        this.rerankerClient = rerankerClient;
        this.hybridSearchService = hybridSearchService;
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.iterationsSummary = DistributionSummary.builder("spectra.agentic.iterations")
                .description("Distribution du nombre d'itérations de recherche agentique")
                .register(meterRegistry);
        this.durationTimer = Timer.builder("spectra.agentic.duration")
                .description("Durée totale de la boucle agentique")
                .register(meterRegistry);
    }

    /** Construit le prompt ReAct en fonction de la langue configurée. */
    private String buildReactSystemPrompt() {
        String langClause = buildLanguageClause();
        return String.format(REACT_SYSTEM_PROMPT_TEMPLATE, langClause);
    }

    /** Construit le prompt fallback avec contexte injecté et clause de langue. */
    private String buildFallbackSystemPrompt(String context) {
        String langClause = buildLanguageClause();
        String template = String.format(FALLBACK_SYSTEM_PROMPT_TEMPLATE, langClause);
        return String.format(template, context);
    }

    private String buildLanguageClause() {
        String lang = props.agenticRag() != null
                ? props.agenticRag().effectiveResponseLanguage() : "fr";
        return switch (lang.toLowerCase()) {
            case "en"   -> "Respond in English.";
            case "auto" -> "";  // le LLM répond dans la langue de la question
            default     -> "Réponds en français.";
        };
    }

    /**
     * Élimine les chunks qui dépassent le budget de tokens de contexte.
     * Conserve les premiers chunks (supposés les plus pertinents par ordre de retrieval)
     * et s'arrête dès que le budget est atteint, en réservant {@code RESPONSE_RESERVE_TOKENS}.
     */
    private List<String> fitToContextBudget(List<String> chunks, int maxTokens) {
        int budget = maxTokens - RESPONSE_RESERVE_TOKENS;
        if (budget <= 0) return List.of();
        List<String> result = new ArrayList<>();
        int used = 0;
        for (String chunk : chunks) {
            int cost = chunk.length() / CHARS_PER_TOKEN;
            if (used + cost > budget) break;
            result.add(chunk);
            used += cost;
        }
        return result;
    }

    // ---------- API publique -----------------------------------------------

    /**
     * Exécute la boucle ReAct à partir du contexte initial fourni par {@link RagService}.
     *
     * @param request         requête utilisateur
     * @param initialChunks   chunks récupérés par la phase de retrieval initiale
     * @param initialMetadatas métadonnées associées (index-alignées avec {@code initialChunks})
     * @param initialDistances distances vectorielles associées
     * @param rerankApplied   {@code true} si le re-ranking a déjà été appliqué sur les chunks initiaux
     * @param hybridApplied   {@code true} si la recherche hybride a été utilisée pour le retrieval initial
     * @return {@link QueryResponse} enrichi des champs agentiques
     */
    public QueryResponse query(QueryRequest request,
                                List<String> initialChunks,
                                List<Map<String, String>> initialMetadatas,
                                List<Double> initialDistances,
                                boolean rerankApplied,
                                boolean hybridApplied) {

        long start = System.currentTimeMillis();

        int maxIterations = props.agenticRag() != null
                ? props.agenticRag().effectiveMaxIterations() : 3;
        int maxContextTokens = props.agenticRag() != null
                ? props.agenticRag().effectiveMaxContextTokens() : 3000;

        String collectionName = request.collection() != null ? request.collection()
                : (props.chromadb() != null ? props.chromadb().effectiveCollection() : "spectra_documents");
        String collectionId = chromaDbClient.getOrCreateCollection(collectionName);

        // Contexte courant (mutable) + ensemble d'anti-duplication
        List<String>               contextChunks    = new ArrayList<>(initialChunks);
        List<Map<String, String>>  contextMetadatas = new ArrayList<>(initialMetadatas);
        List<Double>               contextDistances = new ArrayList<>(initialDistances);
        Set<String>                seenTexts        = new HashSet<>(initialChunks);

        int    iterations   = 0;
        String finalAnswer  = null;
        QueryResponse.AgenticStopReason stopReason = null;

        // ── Boucle ReAct ────────────────────────────────────────────────────
        while (iterations < maxIterations) {

            List<String> budgetedChunks = fitToContextBudget(contextChunks, maxContextTokens);
            List<Map<String, String>> budgetedMetas = contextMetadatas.subList(0, budgetedChunks.size());
            if (budgetedChunks.size() < contextChunks.size()) {
                log.debug("Agentic RAG itération {} — budget tokens: {} chunks retenus sur {} (max {} tokens)",
                        iterations + 1, budgetedChunks.size(), contextChunks.size(), maxContextTokens);
            }

            String contextStr = buildContextString(budgetedChunks, budgetedMetas);

            if (contextStr.isBlank()) {
                finalAnswer = "Aucun document pertinent trouvé dans la base de connaissances.";
                stopReason = QueryResponse.AgenticStopReason.ANSWER;
                break;
            }

            String userMsg = "Question : " + request.question()
                    + "\n\n=== CONTEXTE DISPONIBLE ===\n" + contextStr
                    + "\n=== FIN DU CONTEXTE ===";

            log.debug("Agentic RAG itération {} — {} chunks en contexte", iterations + 1, budgetedChunks.size());
            String llmResponse = llmClient.chat(buildReactSystemPrompt(), userMsg);

            Matcher actionMatcher = ACTION_PATTERN.matcher(llmResponse);
            if (!actionMatcher.find()) {
                // Le LLM n'a pas suivi le format : traitement comme réponse directe
                log.warn("Agentic RAG itération {} : format inattendu, traitement comme réponse directe", iterations + 1);
                finalAnswer = llmResponse.trim();
                stopReason = QueryResponse.AgenticStopReason.FORMAT_ERROR;
                break;
            }

            String action = actionMatcher.group(1).toUpperCase();

            if ("ANSWER".equals(action)) {
                Matcher responseMatcher = RESPONSE_PATTERN.matcher(llmResponse);
                finalAnswer = responseMatcher.find()
                        ? responseMatcher.group(1).trim()
                        : extractFallbackAnswer(llmResponse);
                stopReason = QueryResponse.AgenticStopReason.ANSWER;
                log.info("Agentic RAG : réponse obtenue après {} itération(s)", iterations + 1);
                break;

            } else { // SEARCH
                iterations++;
                Matcher queryMatcher = QUERY_PATTERN.matcher(llmResponse);
                if (!queryMatcher.find()) {
                    log.warn("Agentic RAG itération {} : ACTION=SEARCH sans QUERY valide — arrêt de la boucle", iterations);
                    stopReason = QueryResponse.AgenticStopReason.FORMAT_ERROR;
                    break;
                }
                String refinedQuery = queryMatcher.group(1).trim();
                log.info("Agentic RAG itération {} : recherche complémentaire «{}»", iterations, refinedQuery);

                List<RetrievedChunk> newChunks = retrieveAdditionalChunks(
                        refinedQuery, collectionId, collectionName, request.maxContextChunks(), seenTexts);

                if (newChunks.isEmpty()) {
                    log.info("Agentic RAG itération {} : aucun nouveau chunk — arrêt de la boucle", iterations);
                    stopReason = QueryResponse.AgenticStopReason.NO_NEW_CHUNKS;
                    break;
                }
                for (RetrievedChunk c : newChunks) {
                    contextChunks.add(c.text());
                    contextMetadatas.add(Map.of("sourceFile", c.sourceFile()));
                    contextDistances.add(c.distance());
                }
                log.info("Agentic RAG itération {} : {} nouveau(x) chunk(s) ajouté(s) au contexte",
                        iterations, newChunks.size());
            }
        }

        // ── Fallback final si la boucle s'est épuisée ──────────────────────
        if (finalAnswer == null) {
            log.info("Agentic RAG : max itérations atteint ({}), génération directe", maxIterations);
            stopReason = QueryResponse.AgenticStopReason.MAX_ITERATIONS;
            int limit = Math.min(request.maxContextChunks(), contextChunks.size());
            List<String> fallbackChunks = fitToContextBudget(contextChunks.subList(0, limit), maxContextTokens);
            String contextStr = buildContextString(
                    fallbackChunks,
                    contextMetadatas.subList(0, fallbackChunks.size()));
            finalAnswer = llmClient.chat(buildFallbackSystemPrompt(contextStr), request.question());
        }

        // ── Construction des sources pour la réponse ───────────────────────
        int srcLimit = Math.min(request.maxContextChunks(), contextChunks.size());
        List<QueryResponse.Source> sources = new ArrayList<>(srcLimit);
        for (int i = 0; i < srcLimit; i++) {
            String text       = contextChunks.get(i);
            String sourceFile = contextMetadatas.get(i).getOrDefault("sourceFile", "inconnu");
            double distance   = contextDistances.get(i);
            sources.add(new QueryResponse.Source(
                    text.length() > 200 ? text.substring(0, 200) + "..." : text,
                    sourceFile, distance, null, null));
        }

        long duration = System.currentTimeMillis() - start;
        log.info("Agentic RAG terminé en {}ms — {} itération(s) de recherche, {} source(s)",
                duration, iterations, sources.size());

        // ── Métriques Micrometer ───────────────────────────────────────────
        String stopReasonTag = stopReason != null ? stopReason.name() : "UNKNOWN";
        meterRegistry.counter("spectra.agentic.queries.total",
                "stop_reason", stopReasonTag).increment();
        iterationsSummary.record(iterations);
        durationTimer.record(duration, TimeUnit.MILLISECONDS);

        return new QueryResponse(finalAnswer, sources, duration, rerankApplied, hybridApplied, true, iterations, stopReason);
    }

    // ---------- Helpers privés ---------------------------------------------

    @SuppressWarnings("unchecked")
    private List<RetrievedChunk> retrieveAdditionalChunks(String query, String collectionId,
                                                           String collectionName, int maxChunks,
                                                           Set<String> seen) {
        List<RetrievedChunk> results = new ArrayList<>();
        try {
            List<Float> embedding = embeddingService.embed(query);
            int fetchN = maxChunks * 2; // fetch more to maximise novelty

            if (hybridSearchService.isPresent()) {
                for (HybridSearchService.HybridChunk hc :
                        hybridSearchService.get().search(query, embedding, collectionId, collectionName, fetchN)) {
                    if (!seen.contains(hc.text())) {
                        results.add(new RetrievedChunk(hc.text(), hc.sourceFile(), hc.vectorDistance()));
                        seen.add(hc.text());
                    }
                }
            } else {
                Map<String, Object> raw = chromaDbClient.query(collectionId, embedding, fetchN);
                List<List<String>> docs = (List<List<String>>) raw.get("documents");
                List<List<Map<String, String>>> metas = (List<List<Map<String, String>>>) raw.get("metadatas");
                List<List<Double>> dists = (List<List<Double>>) raw.get("distances");
                if (docs != null && !docs.isEmpty()) {
                    List<String> docList  = docs.getFirst();
                    List<Map<String, String>> metaList = metas != null && !metas.isEmpty() ? metas.getFirst() : List.of();
                    List<Double> distList = dists != null && !dists.isEmpty() ? dists.getFirst() : List.of();
                    for (int i = 0; i < docList.size(); i++) {
                        String t = docList.get(i);
                        if (!seen.contains(t)) {
                            String src = i < metaList.size() ? metaList.get(i).getOrDefault("sourceFile", "inconnu") : "inconnu";
                            double d   = i < distList.size() ? distList.get(i) : 0.0;
                            results.add(new RetrievedChunk(t, src, d));
                            seen.add(t);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Agentic RAG : erreur lors du retrieval complémentaire — {}", e.getMessage());
        }
        return results;
    }

    private String buildContextString(List<String> chunks, List<Map<String, String>> metadatas) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            String sourceFile = metadatas.get(i).getOrDefault("sourceFile", "inconnu");
            sb.append("[Source: ").append(sourceFile).append("]\n");
            sb.append(chunks.get(i)).append("\n\n");
        }
        return sb.toString();
    }

    private String extractFallbackAnswer(String llmResponse) {
        return llmResponse
                .replaceAll("(?i)THOUGHT:.*?(?=\\n|$)", "")
                .replaceAll("(?i)ACTION:\\s*ANSWER", "")
                .trim();
    }

    // ---------- DTO interne ------------------------------------------------

    private record RetrievedChunk(String text, String sourceFile, double distance) {}
}
