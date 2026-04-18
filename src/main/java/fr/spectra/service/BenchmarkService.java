package fr.spectra.service;

import fr.spectra.dto.BenchmarkResult;
import fr.spectra.dto.QueryRequest;
import fr.spectra.dto.QueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mesures de performance des composants critiques de Spectra.
 *
 * <p>Trois benchmarks disponibles :
 * <ul>
 *   <li><b>rag</b> — latence bout en bout d'une requête RAG (embed + search + generate)</li>
 *   <li><b>embedding</b> — débit de vectorisation de textes de taille fixe</li>
 *   <li><b>llm</b> — latence pure LLM (génération sans RAG, contexte minimal)</li>
 * </ul>
 *
 * <p>Les résultats permettent de comparer quantitativement turboquant vs llama.cpp standard
 * et de valider l'impact d'un changement de quantization (Q4_K_M → IQ3_M, etc.).
 */
@Service
public class BenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkService.class);

    // Texte de référence pour le benchmark d'embedding (~512 tokens ≈ 2000 caractères).
    // Utilise un contenu neutre et reproductible pour garantir la comparabilité des mesures.
    private static final String EMBEDDING_TEXT_512 = """
            La gestion des risques est un processus structuré permettant d'identifier, d'évaluer \
            et de traiter les risques susceptibles d'affecter les objectifs d'une organisation. \
            Elle repose sur une analyse méthodique des événements potentiels et de leurs conséquences. \
            Les principales étapes comprennent l'identification des dangers, l'évaluation de leur \
            probabilité et de leur impact, la définition de mesures préventives et correctives, \
            ainsi que le suivi continu de l'efficacité des contrôles mis en place. \
            Une politique de gestion des risques efficace contribue à la résilience opérationnelle \
            et à la conformité réglementaire. Elle intègre la communication des risques aux parties \
            prenantes, la documentation des incidents, et la mise à jour régulière des procédures. \
            Les organisations utilisent des matrices de risques pour prioriser les actions, \
            en combinant des critères de fréquence et de gravité. Le retour d'expérience \
            constitue un levier essentiel pour améliorer continuellement le dispositif. \
            Les normes ISO 31000 et ISO 27005 fournissent un cadre de référence reconnu \
            internationalement pour structurer ces démarches dans tout type d'organisation.
            """.repeat(3); // ~512 tokens

    private static final String LLM_PROMPT =
            "Décris en trois phrases ce qu'est un système de management de la qualité.";

    private final RagService ragService;
    private final EmbeddingService embeddingService;
    private final LlmChatClient chatClient;
    private final ModelRegistryService modelRegistry;

    public BenchmarkService(RagService ragService,
                            EmbeddingService embeddingService,
                            LlmChatClient chatClient,
                            ModelRegistryService modelRegistry) {
        this.ragService = ragService;
        this.embeddingService = embeddingService;
        this.chatClient = chatClient;
        this.modelRegistry = modelRegistry;
    }

    /**
     * Benchmark latence RAG bout en bout : embed(question) + ChromaDB + LLM.
     *
     * @param iterations   nombre de requêtes à exécuter (recommandé : 5–10)
     * @param question     question de référence (doit correspondre au corpus ingéré)
     * @param maxChunks    chunks de contexte injectés (1–3 recommandé)
     */
    public BenchmarkResult benchmarkRag(int iterations, String question, int maxChunks) {
        log.info("[benchmark] RAG — {} itérations, maxChunks={}, question='{}'",
                iterations, maxChunks, question);

        List<Long> latenciesMs = new ArrayList<>();
        List<Long> answerLengths = new ArrayList<>();
        Instant startedAt = Instant.now();
        long totalStart = System.currentTimeMillis();

        for (int i = 0; i < iterations; i++) {
            long t0 = System.currentTimeMillis();
            try {
                QueryResponse response = ragService.query(
                        new QueryRequest(question, maxChunks, null, null));
                long elapsed = System.currentTimeMillis() - t0;
                latenciesMs.add(elapsed);
                answerLengths.add((long) response.answer().length());
                log.debug("[benchmark] RAG iter {}/{}: {}ms, {} chars",
                        i + 1, iterations, elapsed, response.answer().length());
            } catch (Exception e) {
                log.warn("[benchmark] RAG iter {}/{} échouée: {}", i + 1, iterations, e.getMessage());
                latenciesMs.add(-1L);
            }
        }

        long totalDuration = System.currentTimeMillis() - totalStart;
        return buildResult("rag_latency", latenciesMs, answerLengths,
                startedAt, totalDuration, "tokens/s",
                Map.of(
                        "question", question,
                        "maxContextChunks", maxChunks,
                        "successRate", successRate(latenciesMs)
                ));
    }

    /**
     * Benchmark débit d'embedding : vectorisation de textes de taille fixe (~512 tokens).
     *
     * @param iterations   nombre d'appels d'embedding (recommandé : 10–50)
     */
    public BenchmarkResult benchmarkEmbedding(int iterations) {
        log.info("[benchmark] Embedding — {} itérations", iterations);

        List<Long> latenciesMs = new ArrayList<>();
        // Taille fixe en "tokens" (approx.)
        List<Long> tokenCounts = new ArrayList<>();
        Instant startedAt = Instant.now();
        long totalStart = System.currentTimeMillis();

        for (int i = 0; i < iterations; i++) {
            long t0 = System.currentTimeMillis();
            try {
                embeddingService.embed(EMBEDDING_TEXT_512);
                long elapsed = System.currentTimeMillis() - t0;
                latenciesMs.add(elapsed);
                tokenCounts.add(512L); // taille fixe connue
                log.debug("[benchmark] Embed iter {}/{}: {}ms", i + 1, iterations, elapsed);
            } catch (Exception e) {
                log.warn("[benchmark] Embed iter {}/{} échouée: {}", i + 1, iterations, e.getMessage());
                latenciesMs.add(-1L);
            }
        }

        long totalDuration = System.currentTimeMillis() - totalStart;
        return buildResult("embedding_throughput", latenciesMs, tokenCounts,
                startedAt, totalDuration, "vectors/s",
                Map.of(
                        "textTokensApprox", 512,
                        "textCharsApprox", EMBEDDING_TEXT_512.length(),
                        "successRate", successRate(latenciesMs)
                ));
    }

    /**
     * Benchmark latence LLM pure (sans RAG, sans ChromaDB).
     * Isole la vitesse de génération du modèle de chat.
     *
     * @param iterations   nombre de générations (recommandé : 3–5, lent sur CPU)
     */
    public BenchmarkResult benchmarkLlm(int iterations) {
        log.info("[benchmark] LLM pure — {} itérations", iterations);

        List<Long> latenciesMs = new ArrayList<>();
        List<Long> answerLengths = new ArrayList<>();
        Instant startedAt = Instant.now();
        long totalStart = System.currentTimeMillis();

        for (int i = 0; i < iterations; i++) {
            long t0 = System.currentTimeMillis();
            try {
                String answer = chatClient.chat("Tu es un assistant concis.", LLM_PROMPT);
                long elapsed = System.currentTimeMillis() - t0;
                latenciesMs.add(elapsed);
                answerLengths.add((long) answer.length());
                log.debug("[benchmark] LLM iter {}/{}: {}ms, {} chars",
                        i + 1, iterations, elapsed, answer.length());
            } catch (Exception e) {
                log.warn("[benchmark] LLM iter {}/{} échouée: {}", i + 1, iterations, e.getMessage());
                latenciesMs.add(-1L);
            }
        }

        long totalDuration = System.currentTimeMillis() - totalStart;
        return buildResult("llm_latency", latenciesMs, answerLengths,
                startedAt, totalDuration, "tokens/s",
                Map.of(
                        "prompt", LLM_PROMPT,
                        "successRate", successRate(latenciesMs)
                ));
    }

    /**
     * Suite complète : les trois benchmarks enchaînés.
     * Utiliser avec parcimonie (plusieurs minutes sur CPU).
     */
    public Map<String, BenchmarkResult> runFullSuite(String ragQuestion) {
        Map<String, BenchmarkResult> results = new LinkedHashMap<>();
        results.put("embedding", benchmarkEmbedding(10));
        results.put("llm",       benchmarkLlm(3));
        results.put("rag",       benchmarkRag(5, ragQuestion, 2));
        return results;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private BenchmarkResult buildResult(String name, List<Long> latenciesMs,
                                        List<Long> unitCounts, Instant startedAt,
                                        long totalDuration, String throughputUnit,
                                        Map<String, Object> extraDetails) {
        List<Long> valid = latenciesMs.stream().filter(v -> v >= 0).sorted().toList();
        String model = modelRegistry.getActiveChatModel();

        if (valid.isEmpty()) {
            return new BenchmarkResult(name, model, latenciesMs.size(),
                    0, 0, 0, 0, 0.0, 0.0, throughputUnit, startedAt, totalDuration,
                    extraDetails);
        }

        long min = valid.get(0);
        long max = valid.get(valid.size() - 1);
        long p50 = BenchmarkResult.percentile(valid, 50);
        long p95 = BenchmarkResult.percentile(valid, 95);
        double avg = valid.stream().mapToLong(Long::longValue).average().orElse(0);

        // Débit : unités traitées par seconde sur la médiane
        long avgUnitCount = unitCounts.stream()
                .filter(v -> v >= 0)
                .mapToLong(Long::longValue)
                .sum() / Math.max(1, valid.size());
        double throughput = p50 > 0 ? (avgUnitCount * 1000.0) / p50 : 0;

        // Estimation tokens/s pour LLM et RAG : chars / 4 ≈ tokens
        double adjustedThroughput = throughputUnit.equals("tokens/s")
                ? throughput / 4.0
                : throughput;

        Map<String, Object> details = new LinkedHashMap<>(extraDetails);
        details.put("model", model);
        details.put("validIterations", valid.size());

        return new BenchmarkResult(name, model, latenciesMs.size(),
                min, max, p50, p95, avg,
                Math.round(adjustedThroughput * 10.0) / 10.0,
                throughputUnit, startedAt, totalDuration, details);
    }

    private String successRate(List<Long> latencies) {
        long ok = latencies.stream().filter(v -> v >= 0).count();
        return ok + "/" + latencies.size();
    }
}
