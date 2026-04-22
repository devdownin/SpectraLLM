package fr.spectra.controller;

import fr.spectra.dto.QueryRequest;
import fr.spectra.dto.QueryResponse;
import fr.spectra.service.RagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/query")
@Tag(name = "Query", description = "Interrogation RAG du modèle")
public class QueryController {

    private final RagService ragService;

    public QueryController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping
    @Operation(summary = "Interroger le modèle avec recherche contextuelle (RAG)")
    public QueryResponse query(@Valid @RequestBody QueryRequest request) {
        return ragService.query(request);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Interroger le modèle en streaming SSE (sources → token* → done)")
    public Flux<ServerSentEvent<String>> queryStream(@Valid @RequestBody QueryRequest request) {
        return ragService.queryStream(request);
    }
}
