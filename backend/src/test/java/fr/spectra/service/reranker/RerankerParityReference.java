package fr.spectra.service.reranker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.spectra.service.RerankerClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Référence de reranking capturée depuis une implémentation donnée : pour chaque requête du
 * corpus, l'ordre des passages retenus et leurs scores.
 *
 * <p>C'est l'artefact central de l'étape 1 de la migration du reranker
 * ({@code docs/process/audit-python-java.fr.md}, lot 2) : la référence doit être prise sur le
 * service Python <b>avant</b> qu'une implémentation Java existe, faute de quoi il n'y a plus
 * rien à quoi comparer — et une régression de reranking ne casse rien, elle dégrade seulement
 * les réponses.
 *
 * <p>La provenance est enregistrée avec les résultats, en particulier :
 * <ul>
 *   <li>{@code model} — un classement n'a de sens qu'à modèle constant ;</li>
 *   <li>{@code scoreScale} — {@code sentence-transformers} applique une sigmoïde sur un
 *       cross-encoder à un logit, là où un runtime ONNX brut rend le logit. La sigmoïde étant
 *       monotone, <b>l'ordre est identique</b> mais l'échelle des scores change — et ces scores
 *       sont exposés par l'API ({@code rerankScores}), donc comparables entre campagnes de
 *       benchmark. Le noter explicitement évite de croire à une régression là où il n'y a qu'un
 *       changement d'échelle, et inversement ;</li>
 *   <li>{@code corpusFingerprint} — invalide la référence si le benchmark source a changé.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record RerankerParityReference(
        String capturedAt,
        String source,
        String model,
        String scoreScale,
        String corpusFingerprint,
        int documentCount,
        int topN,
        List<QueryResults> queries) {

    /** Résultats d'une requête : rangs dans l'ordre produit. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record QueryResults(String query, List<Ranked> results) {}

    /** Un passage retenu : son index dans le vivier soumis, et son score. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Ranked(int index, double score) {}

    /** Échelle de score inconnue : la capture ne peut pas la deviner, elle est déclarée. */
    static final String SCALE_UNKNOWN = "unknown";

    private static ObjectMapper mapper() {
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    static RerankerParityReference capture(RerankerClient client,
                                           RerankerParityCorpus corpus,
                                           String source,
                                           String model,
                                           String scoreScale) {
        List<QueryResults> captured = corpus.queries().stream()
                .map(query -> new QueryResults(query,
                        client.rerank(query, corpus.documents(), RerankerParityCorpus.TOP_N).stream()
                                .map(r -> new Ranked(r.index(), r.score()))
                                .toList()))
                .toList();
        return new RerankerParityReference(
                Instant.now().toString(), source, model, scoreScale,
                corpus.fingerprint(), corpus.documents().size(),
                RerankerParityCorpus.TOP_N, captured);
    }

    /** Rejoue le corpus sur {@code client} et renvoie les résultats sous la même forme. */
    static List<QueryResults> replay(RerankerClient client, RerankerParityCorpus corpus, int topN) {
        return corpus.queries().stream()
                .map(query -> new QueryResults(query,
                        client.rerank(query, corpus.documents(), topN).stream()
                                .map(r -> new Ranked(r.index(), r.score()))
                                .toList()))
                .toList();
    }

    void writeTo(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        mapper().writeValue(path.toFile(), this);
    }

    static RerankerParityReference readFrom(Path path) throws IOException {
        return mapper().readValue(path.toFile(), RerankerParityReference.class);
    }
}
