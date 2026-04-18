package fr.spectra.controller;

import fr.spectra.dto.IngestionTask;
import fr.spectra.dto.UrlIngestionRequest;
import fr.spectra.service.IngestionService;
import fr.spectra.service.UrlIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ingest")
@Tag(name = "Ingestion", description = "Upload et traitement de documents")
public class IngestController {

    private final IngestionService ingestionService;
    private final UrlIngestionService urlIngestionService;

    public IngestController(IngestionService ingestionService, UrlIngestionService urlIngestionService) {
        this.ingestionService = ingestionService;
        this.urlIngestionService = urlIngestionService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload et ingestion de documents (PDF, DOCX, JSON, XML, ZIP)")
    public Map<String, String> ingest(@RequestParam("files") List<MultipartFile> files) {
        if (files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aucun fichier fourni");
        }

        String taskId = ingestionService.submit(files);
        return Map.of("taskId", taskId, "status", "PENDING");
    }

    @PostMapping("/url")
    @Operation(summary = "Ingestion asynchrone d'une liste d'URLs (HTML, PDF, TXT)")
    public Map<String, String> ingestUrls(@Valid @RequestBody UrlIngestionRequest request) {
        if (request.urls() == null || request.urls().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aucune URL fournie");
        }
        IngestionTask task = urlIngestionService.submit(request.urls());
        return Map.of("taskId", task.taskId(), "status", task.status().name());
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "Suivi de l'avancement d'une ingestion")
    public IngestionTask getTaskStatus(@PathVariable String taskId) {
        IngestionTask task = ingestionService.getTask(taskId);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tâche inconnue: " + taskId);
        }
        return task;
    }
}
