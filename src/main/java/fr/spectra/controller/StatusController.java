package fr.spectra.controller;

import fr.spectra.dto.ServiceStatus;
import fr.spectra.dto.SystemStatusResponse;
import fr.spectra.service.ChromaDbClient;
import fr.spectra.service.CrossEncoderRerankerClient;
import fr.spectra.service.EmbeddingClient;
import fr.spectra.service.FtsService;
import fr.spectra.service.LlmChatClient;
import fr.spectra.service.extraction.LayoutParserClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/status")
@Tag(name = "Status", description = "État de santé du système")
public class StatusController {

    private final LlmChatClient llmChatClient;
    private final EmbeddingClient embeddingClient;
    private final ChromaDbClient chromaDbClient;
    private final Optional<FtsService> ftsService;
    private final Optional<LayoutParserClient> layoutParserClient;
    private final Optional<CrossEncoderRerankerClient> rerankerClient;

    public StatusController(LlmChatClient llmChatClient,
                            EmbeddingClient embeddingClient,
                            ChromaDbClient chromaDbClient,
                            Optional<FtsService> ftsService,
                            Optional<LayoutParserClient> layoutParserClient,
                            Optional<CrossEncoderRerankerClient> rerankerClient) {
        this.llmChatClient = llmChatClient;
        this.embeddingClient = embeddingClient;
        this.chromaDbClient = chromaDbClient;
        this.ftsService = ftsService;
        this.layoutParserClient = layoutParserClient;
        this.rerankerClient = rerankerClient;
    }

    @GetMapping
    @Operation(summary = "État de santé de tous les services")
    public SystemStatusResponse getStatus() {
        List<ServiceStatus> services = new ArrayList<>();
        services.add(llmChatClient.checkHealth());
        services.add(embeddingClient.checkHealth());
        services.add(chromaDbClient.checkHealth());
        layoutParserClient.ifPresent(c -> services.add(c.checkHealth()));
        rerankerClient.ifPresent(c -> services.add(c.checkHealth()));

        return new SystemStatusResponse(
                "spectra-api",
                "0.1.0-SNAPSHOT",
                Instant.now(),
                services
        );
    }

    /**
     * État de l'index BM25 Full-Text Search.
     *
     * @param collection nom de la collection (optionnel — agrégat si absent)
     */
    @GetMapping("/fts")
    @Operation(summary = "État de l'index BM25 Full-Text Search")
    public FtsService.FtsStatus getFtsStatus(
            @RequestParam(required = false) String collection) {
        if (ftsService.isEmpty()) {
            return new FtsService.FtsStatus(false, 0, "(disabled)");
        }
        return collection != null
                ? ftsService.get().getStatus(collection)
                : ftsService.get().getAggregatedStatus();
    }
}
