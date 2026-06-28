package fr.spectra.dto;

/**
 * Métriques de <b>retrieval</b> (qualité de la récupération, indépendantes de la génération).
 *
 * <p>Calculées de manière <b>déterministe</b> (sans LLM-juge) à partir des sources renvoyées par
 * le pipeline et du champ {@code expectedSources} du benchmark. Une question ne contribue que si
 * elle est annotée d'au moins une source attendue ; sinon elle est ignorée (d'où
 * {@code evaluatedQuestions} qui peut être inférieur au total du benchmark).</p>
 *
 * <ul>
 *   <li>{@code hitRate} (Hit@k) — part des questions dont au moins une source attendue figure dans
 *       le top-k récupéré.</li>
 *   <li>{@code mrr} (Mean Reciprocal Rank) — moyenne de 1/rang de la première source pertinente
 *       (0 si aucune dans le top-k).</li>
 *   <li>{@code recallAtK} — part moyenne des sources attendues effectivement retrouvées dans le top-k.</li>
 * </ul>
 */
public record RetrievalMetrics(
        int evaluatedQuestions,
        int k,
        double hitRate,
        double mrr,
        double recallAtK
) {}
