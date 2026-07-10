package fr.spectra.controller;

import fr.spectra.dto.ServiceStatus;
import fr.spectra.service.ChromaDbClient;
import fr.spectra.service.CrossEncoderRerankerClient;
import fr.spectra.service.EmbeddingClient;
import fr.spectra.service.LlmChatClient;
import fr.spectra.service.extraction.LayoutParserClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/health")
@Tag(name = "Health", description = "Santé des services externes")
public class HealthController {

    private final LlmChatClient llmChatClient;
    private final EmbeddingClient embeddingClient;
    private final ChromaDbClient chromaDbClient;
    private final Optional<LayoutParserClient> layoutParserClient;
    private final Optional<CrossEncoderRerankerClient> rerankerClient;

    public HealthController(LlmChatClient llmChatClient,
                            EmbeddingClient embeddingClient,
                            ChromaDbClient chromaDbClient,
                            Optional<LayoutParserClient> layoutParserClient,
                            Optional<CrossEncoderRerankerClient> rerankerClient) {
        this.llmChatClient = llmChatClient;
        this.embeddingClient = embeddingClient;
        this.chromaDbClient = chromaDbClient;
        this.layoutParserClient = layoutParserClient;
        this.rerankerClient = rerankerClient;
    }

    @GetMapping("/services")
    @Operation(summary = "État de santé de tous les services externes")
    public List<ServiceStatus> getServicesHealth() {
        List<ServiceStatus> services = new ArrayList<>();
        services.add(llmChatClient.checkHealth());
        services.add(embeddingClient.checkHealth());
        services.add(chromaDbClient.checkHealth());
        layoutParserClient.ifPresent(c -> services.add(c.checkHealth()));
        rerankerClient.ifPresent(c -> services.add(c.checkHealth()));
        return services;
    }
}
