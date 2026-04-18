package fr.spectra.controller;

import fr.spectra.dto.FineTuningJob;
import fr.spectra.dto.FineTuningRequest;
import fr.spectra.service.FineTuningService;
import fr.spectra.service.LlmClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/fine-tuning")
@Tag(name = "Fine-tuning", description = "Pilotage du fine-tuning LoRA/QLoRA")
public class FineTuningController {

    private final FineTuningService fineTuningService;
    private final LlmClient llmClient;

    public FineTuningController(FineTuningService fineTuningService, LlmClient llmClient) {
        this.fineTuningService = fineTuningService;
        this.llmClient = llmClient;
    }

    @PostMapping
    @Operation(summary = "Lancer un job de fine-tuning QLoRA")
    public Map<String, String> startFineTuning(@Valid @RequestBody FineTuningRequest request) {
        String jobId = fineTuningService.submit(request);
        return Map.of("jobId", jobId, "status", "PENDING");
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "Suivi d'un job de fine-tuning (progression, epoch, loss)")
    public FineTuningJob getJobStatus(@PathVariable String jobId) {
        FineTuningJob job = fineTuningService.getJob(jobId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job inconnu: " + jobId);
        }
        return job;
    }

    @GetMapping
    @Operation(summary = "Lister tous les jobs de fine-tuning")
    public List<FineTuningJob> listJobs() {
        return fineTuningService.getAllJobs();
    }

    @GetMapping("/models")
    @Operation(summary = "Lister les modèles disponibles sur le serveur LLM")
    public List<Map<String, Object>> listModels() {
        return llmClient.listModels();
    }
}
