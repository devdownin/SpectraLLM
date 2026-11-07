package fr.spectra.mcp;

import fr.spectra.config.SpectraProperties;
import fr.spectra.controller.StatusController;
import fr.spectra.dto.CitationReport;
import fr.spectra.dto.QueryRequest;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import fr.spectra.dto.QueryResponse;
import fr.spectra.dto.SystemStatusResponse;
import fr.spectra.service.ChromaDbClient;
import fr.spectra.service.GedService;
import fr.spectra.service.RagService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Outils MCP de Spectra — tous en lecture seule.
 *
 * <p>Aucun outil n'ingère, ne supprime ni ne reconfigure : un agent qui interroge le corpus n'a
 * pas à pouvoir le modifier, et un outil d'écriture listé dans {@code tools/list} est un outil
 * qu'un modèle finira par appeler. Le bean n'existe que si {@code spectra.mcp.enabled} est vrai —
 * Spring AI scanne les {@code @McpTool} de tous les beans du contexte.</p>
 *
 * <p>Deux outils pour interroger, et la différence est voulue : {@code spectra_search} rend les
 * passages et leurs scores sans appeler le LLM (rapide, vérifiable, ce dont un agent a besoin pour
 * citer) ; {@code spectra_ask} rend la réponse générée avec ses sources.</p>
 */
@Component
@ConditionalOnProperty(name = "spectra.mcp.enabled", havingValue = "true")
public class SpectraMcpTools {

    /** Borne la sortie : un chunk de plusieurs pages remplirait la fenêtre de l'agent. */
    static final int MAX_PASSAGE_CHARS = 2_000;
    static final int MAX_PASSAGES = 20;

    private final RagService ragService;
    private final ChromaDbClient chromaDbClient;
    private final GedService gedService;
    private final StatusController statusController;
    private final McpCallJournal journal;
    private final String defaultCollection;

    public SpectraMcpTools(RagService ragService, ChromaDbClient chromaDbClient, GedService gedService,
                           StatusController statusController, McpCallJournal journal,
                           SpectraProperties properties) {
        this.ragService = ragService;
        this.chromaDbClient = chromaDbClient;
        this.gedService = gedService;
        this.statusController = statusController;
        this.journal = journal;
        this.defaultCollection = properties.chromadb().effectiveCollection();
    }

    public record Passage(int rank, String sourceFile, String text, boolean textTruncated,
                          double distance, Float rerankScore, Float bm25Score,
                          String documentId, String revision, String chunkIndex, String section, String sourceUrl) {}

    public record SearchResult(String collection, List<Passage> passages, boolean rerankApplied,
                               boolean hybridApplied, String note) {}

    public record AskResult(String collection, String answer, List<Passage> sources, long durationMs,
                            boolean rerankApplied, boolean hybridSearchApplied, CitationReport citations) {}

    public record DocumentList(String collection, boolean collectionExists, Map<String, Integer> chunksBySource,
                               int sourceCount, long chunkCount) {}

    public record DocumentPreview(String sha256, List<String> chunks, boolean truncated) {}

    @McpTool(name = "spectra_search", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = false),
            description = """
            Retrieve the passages of the indexed corpus most relevant to a question, with their
            source file and scores. Does not call the language model: use it to find and cite
            evidence. An empty `passages` list means nothing in the collection matched, not that
            the collection is empty — call spectra_list_documents to tell the two apart.
            """)
    public SearchResult search(
            @McpToolParam(description = "The question or keywords to search for") String question,
            @McpToolParam(required = false, description = "Collection name; the configured default when omitted")
            String collection,
            @McpToolParam(required = false, description = "Passages to return, 1 to 20; default 5")
            Integer maxPassages, McpSyncServerExchange exchange) {
        return journal.record("spectra_search", arguments("question", question, "collection", collection, "maxPassages", maxPassages),
                () -> McpToolFailure.guard("spectra_search", () -> {
            String coll = authorizedCollection(collection, exchange);
            RagService.RagContext ctx = ragService.retrieveContext(request(question, coll, clamp(maxPassages)));
            List<Passage> passages = passages(coll, ctx.sources());
            String note = passages.isEmpty()
                    ? "No passage matched in collection '%s'.".formatted(coll) : null;
            return new SearchResult(coll, passages, ctx.rerankApplied(), ctx.hybridApplied(), note);
        }));
    }

    @McpTool(name = "spectra_ask", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = false),
            description = """
            Answer a question with retrieval-augmented generation over the indexed corpus, and
            return the answer with the passages it was grounded on. Slower than spectra_search
            since it calls the language model; prefer spectra_search when you only need evidence.
            An answer with no `sources` was not grounded on the corpus.
            """)
    public AskResult ask(
            @McpToolParam(description = "The question to answer") String question,
            @McpToolParam(required = false, description = "Collection name; the configured default when omitted")
            String collection,
            @McpToolParam(required = false, description = "Context passages, 1 to 20; default 5")
            Integer maxPassages, McpSyncServerExchange exchange) {
        return journal.record("spectra_ask", arguments("question", question, "collection", collection, "maxPassages", maxPassages),
                () -> McpToolFailure.guard("spectra_ask", () -> {
            String coll = authorizedCollection(collection, exchange);
            QueryResponse response = ragService.query(request(question, coll, clamp(maxPassages)));
            return new AskResult(coll, response.answer(), passages(coll, response.sources()), response.durationMs(),
                    response.rerankApplied(), response.hybridSearchApplied(), response.citations());
        }));
    }

    @McpTool(name = "spectra_list_documents", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = false),
            description = """
            List the source files indexed in a collection with their chunk count. A collection
            that does not exist is reported as such (`collectionExists: false`) rather than
            created or shown as empty.
            """)
    public DocumentList listDocuments(
            @McpToolParam(required = false, description = "Collection name; the configured default when omitted")
            String collection, McpSyncServerExchange exchange) {
        return journal.record("spectra_list_documents", arguments("collection", collection),
                () -> McpToolFailure.guard("spectra_list_documents", () -> {
            String coll = authorizedCollection(collection, exchange);
            // Lister ne doit pas créer : getOrCreateCollection matérialiserait une collection vide
            // sous un nom mal orthographié, et la réponse dirait « aucun document » au lieu de
            // « cette collection n'existe pas ».
            if (!collectionExists(coll)) {
                return new DocumentList(coll, false, Map.of(), 0, 0);
            }
            Map<String, Integer> sources = chromaDbClient.listSources(chromaDbClient.getOrCreateCollection(coll));
            long chunks = sources.values().stream().mapToLong(Integer::longValue).sum();
            return new DocumentList(coll, true, sources, sources.size(), chunks);
        }));
    }

    @McpTool(name = "spectra_preview_document", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = false),
            description = """
            Return the first indexed chunks of one document, identified by its SHA-256. At most
            12 chunks of 2000 characters; `truncated` says the document holds more.
            """)
    public DocumentPreview previewDocument(
            @McpToolParam(description = "SHA-256 of the document, as listed in the document registry")
            String sha256, McpSyncServerExchange exchange) {
        return journal.record("spectra_preview_document", arguments("sha256", sha256),
                () -> McpToolFailure.guard("spectra_preview_document", () -> {
            McpCaller caller = McpCaller.from(exchange);
            GedService.DocumentPreview preview = caller.scoped()
                    ? gedService.preview(sha256, caller.collections()) : gedService.preview(sha256);
            return new DocumentPreview(sha256, preview.chunks(), preview.truncated());
        }));
    }

    @McpTool(name = "spectra_read_document", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = false),
            description = """
            Read indexed text from the exact document revision returned by spectra_search or spectra_ask.
            Use aroundChunk to start near a cited chunk, or pass nextCursor back as cursor to continue.
            """)
    public McpDocumentRead.Page readDocument(
            @McpToolParam(description = "Document reference returned in a search or answer passage") String documentId,
            @McpToolParam(required = false, description = "Chunk index to start near") Integer aroundChunk,
            @McpToolParam(required = false, description = "Continuation cursor from the previous page") String cursor,
            @McpToolParam(required = false, description = "Character budget between 256 and 16000, default 4000")
            Integer maxChars, McpSyncServerExchange exchange) {
        return journal.record("spectra_read_document", Map.of(), () -> McpToolFailure.guard("spectra_read_document", () -> {
            var ref = McpDocumentRead.parseReference(documentId);
            McpCaller.from(exchange).requireCollection(ref.collection());
            String collectionId = chromaDbClient.listCollections().stream()
                    .filter(collection -> ref.collection().equals(collection.get("name")))
                    .map(collection -> collection.get("id"))
                    .filter(String.class::isInstance).map(String.class::cast)
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Document collection no longer exists"));
            return McpDocumentRead.read(chromaDbClient, collectionId, ref, documentId, aroundChunk, cursor,
                    maxChars == null ? 4_000 : Math.clamp(maxChars, 256, 16_000));
        }));
    }

    @McpTool(name = "spectra_status", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = false),
            description = """
            Report whether each service Spectra depends on (language model, embeddings, vector
            store, and the optional layout parser and reranker) is reachable. Check it before
            concluding that an empty answer means the corpus holds nothing.
            """)
    public SystemStatusResponse status(McpSyncServerExchange exchange) {
        McpCaller.from(exchange);
        return journal.record("spectra_status", Map.of(), statusController::getStatus);
    }

    /** Paires nom/valeur, nulles comprises : le journal écarte lui-même les absentes. */
    private static Map<String, Object> arguments(Object... pairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private QueryRequest request(String question, String collection, int max) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        // Le retrieval résout la collection par getOrCreateCollection : sans ce contrôle, un nom
        // mal orthographié créerait une collection vide et la recherche répondrait « rien trouvé ».
        if (!collectionExists(collection)) {
            throw new IllegalArgumentException(
                    "collection '%s' does not exist; spectra_list_documents names the default one".formatted(collection));
        }
        return new QueryRequest(question, max, null, collection, null, null, null, true);
    }

    private boolean collectionExists(String name) {
        return chromaDbClient.listCollections().stream()
                .anyMatch(c -> name.equals(c.get("name")));
    }

    private String authorizedCollection(String collection, McpSyncServerExchange exchange) {
        McpCaller caller = McpCaller.from(exchange);
        String resolved = collection == null || collection.isBlank() ? defaultCollection : collection.trim();
        if ((collection == null || collection.isBlank()) && caller.scoped()
                && !caller.collections().contains(resolved) && caller.collections().size() == 1) {
            resolved = caller.collections().iterator().next();
        }
        caller.requireCollection(resolved);
        return resolved;
    }

    private static int clamp(Integer maxPassages) {
        return maxPassages == null ? 5 : Math.clamp(maxPassages, 1, MAX_PASSAGES);
    }

    private static List<Passage> passages(String collection, List<QueryResponse.Source> sources) {
        List<Passage> passages = new ArrayList<>();
        int rank = 1;
        for (QueryResponse.Source s : Objects.requireNonNullElse(sources, List.<QueryResponse.Source>of())) {
            var provenance = s.provenance();
            boolean hasFullPassage = provenance != null && provenance.passage() != null;
            String text = hasFullPassage ? provenance.passage() : Objects.requireNonNullElse(s.text(), "");
            boolean truncated = text.length() > MAX_PASSAGE_CHARS || (!hasFullPassage && text.endsWith("..."));
            int end = Math.min(text.length(), MAX_PASSAGE_CHARS);
            if (end < text.length() && Character.isLowSurrogate(text.charAt(end))) end--;
            passages.add(new Passage(rank++, s.sourceFile(),
                    text.substring(0, end), truncated,
                    s.distance(), s.rerankScore(), s.bm25Score(), McpDocumentRead.reference(collection, s),
                    provenance == null ? null : provenance.revision(), provenance == null ? null : provenance.chunkIndex(),
                    provenance == null ? null : provenance.section(), provenance == null ? null : provenance.sourceUrl()));
        }
        return passages;
    }
}
