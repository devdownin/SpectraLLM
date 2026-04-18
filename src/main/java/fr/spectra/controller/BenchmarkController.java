package fr.spectra.controller;

import fr.spectra.dto.BenchmarkResult;
import fr.spectra.service.BenchmarkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Endpoints de benchmark pour mesurer les performances de turboquant dans Spectra.
 *
 * <p>Ces endpoints permettent de :
 * <ul>
 *   <li>Mesurer la latence RAG bout en bout (embed + ChromaDB + LLM)</li>
 *   <li>Mesurer le débit d'embedding (vectorisation)</li>
 *   <li>Mesurer la latence LLM pure (génération sans RAG)</li>
 *   <li>Comparer objectivement différentes quantizations ou versions du fork</li>
 * </ul>
 *
 * <p><b>Avertissement :</b> ces endpoints sont bloquants et peuvent prendre plusieurs
 * minutes sur CPU. Ne pas appeler en production sous charge.
 */
@RestController
@RequestMapping("/api/benchmark")
@Tag(name = "Benchmark", description = "Mesures de performance turboquant (latence, débit, qualité)")
public class BenchmarkController {

    private final BenchmarkService benchmarkService;

    public BenchmarkController(BenchmarkService benchmarkService) {
        this.benchmarkService = benchmarkService;
    }

    @GetMapping("/embedding")
    @Operation(
            summary = "Benchmark débit d'embedding",
            description = "Mesure la latence de vectorisation d'un texte de ~512 tokens. "
                    + "Permet de comparer le débit entre différentes versions de llama-server "
                    + "ou configurations (-b batch_size). "
                    + "Paramètre 'iterations' : nombre d'appels (défaut : 10, max : 50)."
    )
    public BenchmarkResult benchmarkEmbedding(
            @RequestParam(defaultValue = "10") int iterations) {
        int clamped = Math.min(50, Math.max(1, iterations));
        return benchmarkService.benchmarkEmbedding(clamped);
    }

    @GetMapping("/llm")
    @Operation(
            summary = "Benchmark latence LLM pure",
            description = "Mesure la vitesse de génération du modèle de chat sans RAG. "
                    + "Isole la contribution du LLM dans la latence totale d'une requête RAG. "
                    + "Lent sur CPU (~30-120 s par itération). Paramètre 'iterations' : défaut 3, max 10."
    )
    public BenchmarkResult benchmarkLlm(
            @RequestParam(defaultValue = "3") int iterations) {
        int clamped = Math.min(10, Math.max(1, iterations));
        return benchmarkService.benchmarkLlm(clamped);
    }

    @GetMapping("/rag")
    @Operation(
            summary = "Benchmark latence RAG bout en bout",
            description = "Mesure la latence totale d'une requête RAG : embed(question) + "
                    + "recherche ChromaDB + génération LLM. "
                    + "La 'question' doit correspondre au contenu du corpus ingéré pour "
                    + "obtenir des résultats représentatifs. "
                    + "Paramètre 'maxChunks' : nombre de chunks de contexte (1-3 recommandé pour "
                    + "le modèle fine-tuné avec contexte 2048 tokens)."
    )
    public BenchmarkResult benchmarkRag(
            @RequestParam(defaultValue = "5") int iterations,
            @RequestParam(defaultValue = "Quelle est la procédure principale décrite dans les documents ?") String question,
            @RequestParam(defaultValue = "2") int maxChunks) {
        int clampedIter = Math.min(20, Math.max(1, iterations));
        int clampedChunks = Math.min(5, Math.max(1, maxChunks));
        return benchmarkService.benchmarkRag(clampedIter, question, clampedChunks);
    }

    @GetMapping
    @Operation(
            summary = "Suite complète de benchmarks",
            description = "Enchaîne les trois benchmarks (embedding × 10, LLM × 3, RAG × 5). "
                    + "Durée totale estimée : 5-20 minutes sur CPU. "
                    + "Retourne une map 'embedding' / 'llm' / 'rag' avec les métriques de chaque composant. "
                    + "Utiliser après un changement de quantization pour comparer avant/après."
    )
    public Map<String, BenchmarkResult> runFullSuite(
            @RequestParam(defaultValue = "Quelle est la procédure principale décrite dans les documents ?") String ragQuestion) {
        return benchmarkService.runFullSuite(ragQuestion);
    }
}
