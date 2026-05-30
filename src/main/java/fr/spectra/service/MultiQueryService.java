package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Multi-Query RAG — génération de variantes de la question pour améliorer le rappel du retrieval.
 *
 * <p>Pour une question donnée, génère {@code N} reformulations abordant la question sous des
 * angles différents (synonymes, niveau de généralité, termes techniques alternatifs…).
 * Le retrieval est exécuté pour chacune et les résultats sont fusionnés en éliminant les
 * doublons exacts. Augmente le rappel documentaire sans augmenter le bruit.</p>
 *
 * <p>La question originale est toujours incluse en première position dans la liste retournée,
 * ce qui garantit que le retrieval principal est toujours exécuté même si le LLM échoue.</p>
 *
 * <p>Activé via {@code SPECTRA_MULTI_QUERY_ENABLED=true}.
 * Nombre de variantes configurable via {@code SPECTRA_MULTI_QUERY_COUNT} (défaut : 2).</p>
 */
@Service
@ConditionalOnProperty(prefix = "spectra.multi-query", name = "enabled", havingValue = "true")
public class MultiQueryService {

    private static final Logger log = LoggerFactory.getLogger(MultiQueryService.class);

    private static final String MULTI_QUERY_SYSTEM =
            "Tu es un expert en reformulation de requêtes de recherche documentaire. Sois concis.";

    private static final String MULTI_QUERY_PROMPT = """
            Génère %d reformulations différentes de la question suivante pour améliorer la recherche documentaire.
            Chaque reformulation doit aborder la question sous un angle différent \
            (synonymes, niveau de généralité, aspect précis, terme technique alternatif).
            Réponds avec UNE REFORMULATION PAR LIGNE, sans numérotation ni explication.

            Question originale : %s

            Reformulations :""";

    private final LlmChatClient llmClient;
    private final SpectraProperties props;

    public MultiQueryService(LlmChatClient llmClient, SpectraProperties props) {
        this.llmClient = llmClient;
        this.props = props;
    }

    /**
     * Génère {@code N} variantes de la question pour le retrieval multi-requêtes.
     * La question originale est toujours en première position dans la liste retournée.
     * En cas d'échec LLM, retourne une liste contenant uniquement la question originale.
     *
     * @param question question originale à reformuler
     * @return liste [question originale, variante1, variante2, …] (dédupliquée)
     */
    public List<String> generateQueries(String question) {
        int n = props.multiQuery() != null ? props.multiQuery().effectiveQueryCount() : 2;
        String prompt = String.format(MULTI_QUERY_PROMPT, n, question);

        List<String> variants;
        try {
            String raw = llmClient.chat(MULTI_QUERY_SYSTEM, prompt).trim();
            variants = Arrays.stream(raw.split("\n"))
                    .map(String::trim)
                    .filter(s -> !s.isBlank() && !s.equals(question))
                    .limit(n)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Multi-query : génération échouée, utilisation de la question originale uniquement — {}", e.getMessage());
            return List.of(question);
        }

        List<String> queries = new ArrayList<>(variants.size() + 1);
        queries.add(question);
        queries.addAll(variants);
        log.info("Multi-query : {} requêtes générées pour «{}»", queries.size(), question);
        return queries;
    }
}
