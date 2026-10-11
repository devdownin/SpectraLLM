package fr.spectra.mcp;

import fr.spectra.config.SpectraProperties;
import fr.spectra.controller.StatusController;
import fr.spectra.dto.QueryRequest;
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
    private final String defaultCollection;

    public SpectraMcpTools(RagService ragService, ChromaDbClient chromaDbClient, GedService gedService,
                           StatusController statusController, SpectraProperties properties) {
        this.ragService = ragService;
        this.chromaDbClient = chromaDbClient;
        this.gedService = gedService;
        this.statusController = statusController;
        this.defaultCollection = properties.chromadb().effectiveCollection();
    }

    public record Passage(int rank, String sourceFile, String text, boolean textTruncated,
                          double distance, Float rerankScore, Float bm25Score) {}

    public record SearchResult(String collection, List<Passage> passages, boolean rerankApplied,
                               boolean hybridApplied, String note) {}

    public record AskResult(String collection, String answer, List<Passage> sources, long durationMs,
                            boolean rerankApplied, boolean hybridSearchApplied) {}

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
            Integer maxPassages) {
        return McpToolFailure.guard("spectra_search", () -> {
            String coll = collectionOrDefault(collection);
            RagService.RagContext ctx = ragService.retrieveContext(request(question, coll, clamp(maxPassages)));
            List<Passage> passages = passages(ctx.sources());
            String note = passages.isEmpty()
                    ? "No passage matched in collection '%s'.".formatted(coll) : null;
            return new SearchResult(coll, passages, ctx.rerankApplied(), ctx.hybridApplied(), note);
        });
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
            Integer maxPassages) {
        return McpToolFailure.guard("spectra_ask", () -> {
            String coll = collectionOrDefault(collection);
            QueryResponse response = ragService.query(request(question, coll, clamp(maxPassages)));
            return new AskResult(coll, response.answer(), passages(response.sources()), response.durationMs(),
                    response.rerankApplied(), response.hybridSearchApplied());
        });
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
            String collection) {
        return McpToolFailure.guard("spectra_list_documents", () -> {
            String coll = collectionOrDefault(collection);
            // Lister ne doit pas créer : getOrCreateCollection matérialiserait une collection vide
            // sous un nom mal orthographié, et la réponse dirait « aucun document » au lieu de
            // « cette collection n'existe pas ».
            if (!collectionExists(coll)) {
                return new DocumentList(coll, false, Map.of(), 0, 0);
            }
            Map<String, Integer> sources = chromaDbClient.listSources(chromaDbClient.getOrCreateCollection(coll));
            long chunks = sources.values().stream().mapToLong(Integer::longValue).sum();
            return new DocumentList(coll, true, sources, sources.size(), chunks);
        });
    }

    @McpTool(name = "spectra_preview_document", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = false),
            description = """
            Return the first indexed chunks of one document, identified by its SHA-256. At most
            12 chunks of 2000 characters; `truncated` says the document holds more.
            """)
    public DocumentPreview previewDocument(
            @McpToolParam(description = "SHA-256 of the document, as listed in the document registry")
            String sha256) {
        return McpToolFailure.guard("spectra_preview_document", () -> {
            GedService.DocumentPreview preview = gedService.preview(sha256);
            return new DocumentPreview(sha256, preview.chunks(), preview.truncated());
        });
    }

    @McpTool(name = "spectra_status", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = false),
            description = """
            Report whether each service Spectra depends on (language model, embeddings, vector
            store, and the optional layout parser and reranker) is reachable. Check it before
            concluding that an empty answer means the corpus holds nothing.
            """)
    public SystemStatusResponse status() {
        return statusController.getStatus();
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

    private String collectionOrDefault(String collection) {
        return collection == null || collection.isBlank() ? defaultCollection : collection.trim();
    }

    private static int clamp(Integer maxPassages) {
        return maxPassages == null ? 5 : Math.clamp(maxPassages, 1, MAX_PASSAGES);
    }

    private static List<Passage> passages(List<QueryResponse.Source> sources) {
        List<Passage> passages = new ArrayList<>();
        int rank = 1;
        for (QueryResponse.Source s : Objects.requireNonNullElse(sources, List.<QueryResponse.Source>of())) {
            String text = Objects.requireNonNullElse(s.text(), "");
            boolean truncated = text.length() > MAX_PASSAGE_CHARS;
            passages.add(new Passage(rank++, s.sourceFile(),
                    truncated ? text.substring(0, MAX_PASSAGE_CHARS) : text, truncated,
                    s.distance(), s.rerankScore(), s.bm25Score()));
        }
        return passages;
    }
}
