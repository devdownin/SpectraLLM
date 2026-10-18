package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.CitationReport;
import fr.spectra.dto.CitationReport.SentenceCheck;
import fr.spectra.dto.CitationReport.Status;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vérifie, après génération, que chaque phrase citée est étayée par le passage qu'elle cite.
 *
 * <p><b>Pourquoi.</b> Le pipeline sait choisir ses passages ; rien ne contrôlait que la réponse
 * s'y tenait. Un modèle 7B cite volontiers {@code [2]} derrière une affirmation que le passage 2
 * ne contient pas — l'hallucination la plus trompeuse, puisqu'elle porte une source.</p>
 *
 * <p><b>Comment.</b> La réponse est découpée en phrases ; chaque phrase citée (citations retirées)
 * est notée contre ses passages cités par le cross-encoder déjà chargé pour le reranking. Aucun
 * appel LLM, un résultat reproductible. Limite : le cross-encoder juge la pertinence, pas
 * l'implication (cf. {@link CitationReport}).</p>
 */
@Service
public class CitationVerifier {

    private static final Logger log = LoggerFactory.getLogger(CitationVerifier.class);

    /** {@code [3]}, {@code [1, 2]}, {@code [1;4]}. */
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+(?:\\s*[,;]\\s*\\d+)*)]");

    /** Fin de phrase suivie de ses citations, puis d'une phrase qui commence (majuscule). */
    private static final Pattern CITED_PERIOD =
            Pattern.compile("[.!?](?:\\s*\\[\\d+(?:\\s*[,;]\\s*\\d+)*])+(?=\\s+\\p{Lu})");
    /** Citations (et ponctuation) en tête de phrase. */
    private static final Pattern LEADING_CITATIONS =
            Pattern.compile("^(?:\\s*\\[\\d+(?:\\s*[,;]\\s*\\d+)*]\\s*[.,;:]?)+");

    private final Optional<RerankerClient> reranker;
    private final SpectraProperties props;
    private final MeterRegistry meterRegistry;

    public CitationVerifier(Optional<RerankerClient> reranker, SpectraProperties props, MeterRegistry meterRegistry) {
        this.reranker = reranker;
        this.props = props;
        this.meterRegistry = meterRegistry;
    }

    /** Vérification activée et cross-encoder chargé. */
    public boolean isAvailable() {
        boolean enabled = props.rag() == null || props.rag().citationCheckEnabled();
        return enabled && reranker.filter(RerankerClient::isAvailable).isPresent();
    }

    public boolean strict() {
        return props.rag() != null && props.rag().citationStrictEnabled();
    }

    /**
     * @param passages textes des passages, dans l'ordre de leur numéro ({@code [1]} = indice 0)
     * @return vide si la vérification est indisponible ou a échoué — jamais bloquante
     */
    public Optional<CitationReport> verify(String answer, List<String> passages) {
        if (!isAvailable() || answer == null || answer.isBlank() || passages == null || passages.isEmpty()) {
            return Optional.empty();
        }
        float minScore = props.rag() != null ? props.rag().effectiveCitationMinScore() : 0.2f;
        try {
            return Optional.of(check(answer, passages, minScore));
        } catch (RuntimeException e) {
            log.warn("Vérification des citations indisponible : {}", e.getMessage());
            return Optional.empty();
        }
    }

    private CitationReport check(String answer, List<String> passages, float minScore) {
        List<SentenceCheck> checks = new ArrayList<>();
        int supported = 0;
        int unsupported = 0;
        int invalid = 0;
        int uncited = 0;
        for (Sentence sentence : sentences(answer)) {
            if (sentence.citations().isEmpty()) {
                uncited++;
                continue;
            }
            List<Integer> valid = sentence.citations().stream()
                    .filter(n -> n >= 1 && n <= passages.size()).distinct().toList();
            invalid += (int) sentence.citations().stream().filter(n -> n < 1 || n > passages.size()).count();
            String claim = CITATION.matcher(sentence.text()).replaceAll("").strip();
            if (valid.isEmpty() || claim.isEmpty()) {
                checks.add(new SentenceCheck(sentence.text(), sentence.citations(), null, Status.INVALID_CITATION));
                unsupported++;
                continue;
            }
            List<String> cited = valid.stream().map(n -> passages.get(n - 1)).toList();
            float best = reranker.orElseThrow().rerank(claim, cited, cited.size()).stream()
                    .map(RerankerClient.RankedResult::score)
                    .max(Float::compare).orElse(0f);
            Status status = best >= minScore ? Status.SUPPORTED : Status.UNSUPPORTED;
            if (status == Status.SUPPORTED) supported++; else unsupported++;
            checks.add(new SentenceCheck(sentence.text(), sentence.citations(), best, status));
        }
        int cited = supported + unsupported;
        meterRegistry.counter("spectra.rag.citations", "status", "supported").increment(supported);
        meterRegistry.counter("spectra.rag.citations", "status", "unsupported").increment(unsupported);
        return new CitationReport(cited, supported, unsupported, invalid, uncited,
                cited > 0 ? (double) supported / cited : null, minScore, List.copyOf(checks));
    }

    /**
     * Réponse sans ses phrases non étayées (mode strict, réponse non streamée). Les phrases sans
     * citation sont conservées : elles ne prétendent à aucune source.
     *
     * @return {@code fallback} si plus rien ne reste
     */
    public String withoutUnsupported(String answer, CitationReport report, String fallback) {
        List<String> rejected = report.checks().stream()
                .filter(c -> c.status() != Status.SUPPORTED).map(SentenceCheck::sentence).toList();
        if (rejected.isEmpty()) return answer;
        StringBuilder kept = new StringBuilder(answer);
        for (String sentence : rejected) {
            int at = kept.indexOf(sentence);
            if (at >= 0) kept.delete(at, at + sentence.length());
        }
        String cleaned = kept.toString().replaceAll("[ \\t]{2,}", " ").replaceAll("\\n{3,}", "\n\n").strip();
        return cleaned.isEmpty() || CITATION.matcher(cleaned).replaceAll("").isBlank() ? fallback : cleaned;
    }

    record Sentence(String text, List<Integer> citations) {}

    /**
     * Phrases de la réponse et leurs citations. Une citation placée après le point
     * (« … 512. [3] ») est rattachée à la phrase qui la précède.
     */
    static List<Sentence> sentences(String answer) {
        List<Sentence> result = new ArrayList<>();
        BreakIterator it = BreakIterator.getSentenceInstance(Locale.FRENCH);
        it.setText(answer);
        List<String> segments = new ArrayList<>();
        for (int start = it.first(), end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            segments.addAll(splitAfterCitedPeriod(answer.substring(start, end)));
        }
        for (String segment : segments) {
            String text = segment.strip();
            if (text.isEmpty()) continue;
            // BreakIterator coupe après le point : « … 60 °C. [1] La garantie … » place [1] en tête
            // de la phrase suivante. Ces citations de tête reviennent à la phrase précédente.
            Matcher leading = LEADING_CITATIONS.matcher(text);
            if (leading.find() && !result.isEmpty()) {
                String head = leading.group().strip();
                Sentence previous = result.removeLast();
                List<Integer> merged = new ArrayList<>(previous.citations());
                merged.addAll(citationsOf(head));
                result.add(new Sentence(previous.text() + " " + head, List.copyOf(merged)));
                text = text.substring(leading.end()).strip();
                if (text.isEmpty()) continue;
            }
            result.add(new Sentence(text, citationsOf(text)));
        }
        return result;
    }

    /**
     * Le BreakIterator du JDK ne coupe pas « … 60 °C. [1] La garantie … » : la citation qui suit
     * le point lui fait manquer la frontière. On coupe après cette citation.
     */
    private static List<String> splitAfterCitedPeriod(String segment) {
        List<String> parts = new ArrayList<>();
        Matcher m = CITED_PERIOD.matcher(segment);
        int from = 0;
        while (m.find()) {
            parts.add(segment.substring(from, m.end()));
            from = m.end();
        }
        parts.add(segment.substring(from));
        return parts;
    }

    private static List<Integer> citationsOf(String text) {
        List<Integer> numbers = new ArrayList<>();
        Matcher m = CITATION.matcher(text);
        while (m.find()) {
            for (String n : m.group(1).split("\\s*[,;]\\s*")) numbers.add(Integer.parseInt(n));
        }
        return List.copyOf(numbers);
    }
}
