package fr.spectra.service.reranker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * Corpus déterministe de comparaison du reranking, <b>dérivé</b> du benchmark qualité embarqué
 * ({@code benchmarks/highway_benchmark.jsonl}).
 *
 * <p><b>Pourquoi dériver plutôt qu'inventer un corpus.</b> Le dépôt a déjà un jeu curé sur le
 * domaine réel (exploitation autoroutière, en français) ; en dupliquer un second créerait une
 * deuxième vérité à maintenir. Ici, les <em>questions</em> deviennent les requêtes et les
 * <em>réponses de référence</em> deviennent le vivier de passages candidats. Chaque requête est
 * donc confrontée à un passage pertinent et à une douzaine de distracteurs du même domaine, dans
 * le même registre : c'est exactement le cas où un reranker se distingue d'une recherche
 * vectorielle, et donc le cas qu'une régression de portage doit faire apparaître.
 *
 * <p>Les questions marquées {@code answerable: false} n'ont pas de réponse de référence : elles
 * ne contribuent aucun passage, mais restent des requêtes — le cas « aucun candidat pertinent »,
 * où l'ordre produit est arbitraire mais doit rester <b>reproductible</b>.
 *
 * <p>Le corpus étant dérivé d'un fichier partagé qui peut évoluer, il porte une empreinte
 * ({@link #fingerprint()}) enregistrée dans la référence capturée. Une évolution du benchmark
 * invalide donc explicitement la référence, au lieu de la comparer en silence à autre chose.
 */
final class RerankerParityCorpus {

    /** Ressource du benchmark qualité, également consommée par {@code QualityBenchmarkService}. */
    private static final String BENCHMARK_RESOURCE = "benchmarks/highway_benchmark.jsonl";

    /**
     * Nombre de résultats demandés. Fixé (et non « tous ») pour que la référence capture ce que
     * le RAG consomme réellement : un sous-ensemble ordonné, pas un classement complet.
     */
    static final int TOP_N = 5;

    private final List<String> queries;
    private final List<String> documents;
    private final String fingerprint;

    private RerankerParityCorpus(List<String> queries, List<String> documents, String fingerprint) {
        this.queries = queries;
        this.documents = documents;
        this.fingerprint = fingerprint;
    }

    static RerankerParityCorpus load() throws IOException {
        List<String> queries = new ArrayList<>();
        List<String> documents = new ArrayList<>();

        ObjectMapper mapper = new ObjectMapper();
        ClassPathResource resource = new ClassPathResource(BENCHMARK_RESOURCE);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode row = mapper.readTree(line);
                String question = row.path("question").asText("").strip();
                String reference = row.path("reference").asText("").strip();
                if (!question.isEmpty()) {
                    queries.add(question);
                }
                if (!reference.isEmpty()) {
                    documents.add(reference);
                }
            }
        }
        return new RerankerParityCorpus(
                Collections.unmodifiableList(queries),
                Collections.unmodifiableList(documents),
                digest(queries, documents));
    }

    /** Requêtes soumises, dans l'ordre du fichier — l'ordre fait partie de la reproductibilité. */
    List<String> queries() {
        return queries;
    }

    /** Vivier de passages candidats, soumis <b>en entier et dans le même ordre</b> à chaque requête. */
    List<String> documents() {
        return documents;
    }

    /** Empreinte du couple (requêtes, passages) : détecte une référence devenue obsolète. */
    String fingerprint() {
        return fingerprint;
    }

    private static String digest(List<String> queries, List<String> documents) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String value : queries) {
                sha.update(("q:" + value + "\n").getBytes(StandardCharsets.UTF_8));
            }
            for (String value : documents) {
                sha.update(("d:" + value + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return "sha256:" + HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
