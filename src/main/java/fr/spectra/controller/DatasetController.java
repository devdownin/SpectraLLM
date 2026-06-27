package fr.spectra.controller;

import fr.spectra.dto.DatasetStats;
import fr.spectra.service.dataset.DatasetExportService;
import fr.spectra.service.dataset.DatasetGeneratorService;
import fr.spectra.service.dataset.DatasetGeneratorService.GenerationTask;
import fr.spectra.service.dataset.DpoGenerationService;
import fr.spectra.service.dataset.DpoGenerationService.DpoTask;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dataset")
@Tag(name = "Dataset", description = "Génération et export du dataset d'entraînement")
public class DatasetController {

    private final DatasetGeneratorService generatorService;
    private final DatasetExportService exportService;
    private final DpoGenerationService dpoService;

    public DatasetController(DatasetGeneratorService generatorService,
                             DatasetExportService exportService,
                             DpoGenerationService dpoService) {
        this.generatorService = generatorService;
        this.exportService = exportService;
        this.dpoService = dpoService;
    }

    // ── SFT generation ──────────────────────────────────────────────────────

    @GetMapping("/generate")
    @Operation(summary = "Lister toutes les tâches de génération SFT")
    public List<GenerationTask> getAllGenerationTasks() {
        return generatorService.getAllTasks();
    }

    @PostMapping("/generate")
    @Operation(summary = "Lancer la génération de paires d'entraînement depuis les chunks ingérés")
    public ResponseEntity<Map<String, String>> generate(
            @RequestParam(defaultValue = "0") int maxChunks) {
        String taskId = generatorService.submit(maxChunks);
        if (taskId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Une génération est déjà en cours"));
        }
        return ResponseEntity.ok(Map.of("taskId", taskId, "status", "PENDING"));
    }

    @GetMapping("/generate/{taskId}")
    @Operation(summary = "Suivi de l'avancement de la génération")
    public GenerationTask getGenerationStatus(@PathVariable String taskId) {
        GenerationTask task = generatorService.getTask(taskId);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tâche inconnue: " + taskId);
        }
        return task;
    }

    @DeleteMapping("/generate/{taskId}")
    @Operation(summary = "Annuler une génération SFT en cours")
    public Map<String, String> cancelGeneration(@PathVariable String taskId) {
        boolean cancelled = generatorService.cancelTask(taskId);
        if (!cancelled) {
            GenerationTask task = generatorService.getTask(taskId);
            if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tâche inconnue: " + taskId);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Impossible d'annuler (status=" + task.status() + ")");
        }
        return Map.of("taskId", taskId, "status", "CANCELLED");
    }

    // ── DPO generation ───────────────────────────────────────────────────────

    @GetMapping("/dpo/generate")
    @Operation(summary = "Lister toutes les tâches de génération DPO")
    public List<DpoTask> getAllDpoTasks() {
        return dpoService.getAllTasks();
    }

    @PostMapping("/dpo/generate")
    @Operation(summary = "Lancer la génération de paires DPO (chosen/rejected)")
    public ResponseEntity<Map<String, String>> generateDpo(
            @RequestParam(defaultValue = "0") int maxPairs) {
        String taskId = dpoService.submit(maxPairs);
        return ResponseEntity.ok(Map.of("taskId", taskId, "status", "PENDING"));
    }

    @GetMapping("/dpo/generate/{taskId}")
    @Operation(summary = "Suivi de l'avancement d'une tâche DPO")
    public DpoTask getDpoTask(@PathVariable String taskId) {
        DpoTask task = dpoService.getTask(taskId);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tâche DPO inconnue: " + taskId);
        }
        return task;
    }

    @GetMapping("/dpo/stats")
    @Operation(summary = "Statistiques des paires DPO générées")
    public Map<String, Object> getDpoStats() {
        int total = dpoService.getAllPairs().size();
        return Map.of("totalPairs", total, "status", total > 0 ? "READY" : "EMPTY");
    }

    @PostMapping("/dpo/export")
    @Operation(summary = "Exporter les paires DPO au format JSONL {prompt, chosen, rejected}")
    public ResponseEntity<Resource> exportDpo() throws IOException {
        if (dpoService.getAllPairs().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Aucune paire DPO. Lancez d'abord POST /api/dataset/dpo/generate");
        }
        Path file = dpoService.exportJsonl();
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @GetMapping("/stats")
    @Operation(summary = "Statistiques du dataset (paires, catégories, qualité)")
    public DatasetStats getStats() {
        return exportService.getStats();
    }

    @PostMapping("/export")
    @Operation(summary = "Exporter le dataset au format JSONL")
    public ResponseEntity<Resource> export() throws IOException {
        DatasetStats stats = exportService.getStats();
        if (stats.totalPairs() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Aucune paire d'entraînement. Lancez d'abord POST /api/dataset/generate");
        }

        Path file = exportService.exportJsonl();
        Resource resource = new FileSystemResource(file);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
