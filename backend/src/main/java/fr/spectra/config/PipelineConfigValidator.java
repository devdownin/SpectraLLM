package fr.spectra.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validation de cohérence de la configuration au démarrage (<b>fail-fast</b>).
 *
 * <p>Certaines combinaisons de paramètres sont silencieusement absurdes : un overlap de
 * chunking ≥ à la taille de chunk (le découpage ne progresse plus), un lot d'embedding ≤ 0,
 * un re-ranker activé avec 0 candidat à re-noter… Sans contrôle, l'incohérence ne se
 * manifeste qu'à la première ingestion / requête — loin de sa cause. On préfère <b>refuser
 * de démarrer</b> avec un message clair listant TOUTES les incohérences détectées.</p>
 *
 * <p>Le contrôle porte sur les <i>valeurs effectives</i> (défauts appliqués) ; une
 * configuration par défaut est cohérente et démarre sans bruit.</p>
 */
@Component
public class PipelineConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(PipelineConfigValidator.class);

    private final SpectraProperties props;
    private final int maxActiveIngestions;

    public PipelineConfigValidator(
            SpectraProperties props,
            @Value("${spectra.pipeline.max-active-ingestions:0}") int maxActiveIngestions) {
        this.props = props;
        this.maxActiveIngestions = maxActiveIngestions;
    }

    @PostConstruct
    public void validate() {
        List<String> errors = new ArrayList<>();

        SpectraProperties.PipelineProperties pipeline = props.pipeline();
        if (pipeline != null) {
            int maxTokens = pipeline.chunkMaxTokens();
            int overlap = pipeline.chunkOverlapTokens();
            if (maxTokens <= 0) {
                errors.add("spectra.pipeline.chunk-max-tokens doit être > 0 (actuel : " + maxTokens + ")");
            }
            if (overlap < 0) {
                errors.add("spectra.pipeline.chunk-overlap-tokens doit être ≥ 0 (actuel : " + overlap + ")");
            }
            if (overlap >= maxTokens) {
                errors.add("spectra.pipeline.chunk-overlap-tokens (" + overlap + ") doit être < "
                        + "chunk-max-tokens (" + maxTokens + ") — sinon le découpage ne progresse pas.");
            }
            if (pipeline.embeddingBatchSize() <= 0) {
                errors.add("spectra.pipeline.embedding-batch-size doit être > 0 (actuel : "
                        + pipeline.embeddingBatchSize() + ")");
            }
        }

        if (maxActiveIngestions < 0) {
            errors.add("spectra.pipeline.max-active-ingestions doit être ≥ 0 (0 = illimité ; actuel : "
                    + maxActiveIngestions + ")");
        }

        SpectraProperties.RerankerProperties reranker = props.reranker();
        if (reranker != null && reranker.isEnabled() && reranker.effectiveTopCandidates() <= 0) {
            errors.add("spectra.reranker.top-candidates doit être > 0 quand le re-ranker est activé "
                    + "(actuel : " + reranker.effectiveTopCandidates() + ")");
        }

        SpectraProperties.MultiQueryProperties multiQuery = props.multiQuery();
        if (multiQuery != null && multiQuery.isEnabled() && multiQuery.effectiveQueryCount() < 1) {
            errors.add("spectra.multi-query.query-count doit être ≥ 1 quand le multi-query est activé "
                    + "(actuel : " + multiQuery.effectiveQueryCount() + ")");
        }

        SpectraProperties.LongContextRagProperties longContext = props.longContextRag();
        if (longContext != null && longContext.isEnabled() && longContext.effectiveMaxCollectionChunks() <= 0) {
            errors.add("spectra.long-context-rag.max-collection-chunks doit être > 0 quand le "
                    + "long-context RAG est activé (actuel : " + longContext.effectiveMaxCollectionChunks() + ")");
        }

        SpectraProperties.HybridSearchProperties hybrid = props.hybridSearch();
        if (hybrid != null && hybrid.isEnabled()) {
            if (hybrid.effectiveTopBm25() <= 0) {
                errors.add("spectra.hybrid-search.top-bm25 doit être > 0 quand la recherche hybride "
                        + "est activée (actuel : " + hybrid.effectiveTopBm25() + ")");
            }
            if (hybrid.effectiveBm25Weight() < 0) {
                errors.add("spectra.hybrid-search.bm25-weight doit être ≥ 0 (actuel : "
                        + hybrid.effectiveBm25Weight() + ")");
            }
        }

        if (!errors.isEmpty()) {
            String message = "Configuration incohérente — démarrage refusé :\n  - "
                    + String.join("\n  - ", errors);
            log.error(message);
            throw new IllegalStateException(message);
        }
        log.info("Validation de la configuration du pipeline : OK");
    }
}
