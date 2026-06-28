package fr.spectra.dto;

import java.util.List;

/**
 * Requête d'ablation : liste de configurations (<b>bras</b>) à évaluer sur le benchmark.
 *
 * <p>Si {@code arms} est vide ou {@code null}, une matrice par défaut est utilisée : « LLM seul »
 * (sans RAG) vs « RAG » sur le modèle actif, afin de mesurer le gain brut du RAG sans paramétrage.</p>
 */
public record AblationRequest(
        List<Arm> arms,
        Integer maxContextChunks,
        /** Nombre de répétitions par bras pour estimer la dispersion (moyenne ± écart-type). Défaut 1. */
        Integer runs
) {
    /**
     * Un bras d'ablation : une configuration à mesurer.
     *
     * @param label     nom lisible affiché dans le rapport (ex. « baseline », « + rerank », « + fine-tuning »)
     * @param model     modèle à utiliser ; {@code null}/vide = modèle actif (basculé temporairement
     *                  puis restauré pour comparer base vs fine-tuné)
     * @param useRag    {@code true} = pipeline RAG complet ; {@code false} = LLM seul (sans retrieval)
     * @param overrides surcharges des modules d'optimisation (rerank, hybride, multi-query…) ;
     *                  {@code null} = configuration de déploiement par défaut
     */
    public record Arm(String label, String model, Boolean useRag, RagOverrides overrides) {
        public Arm {
            if (useRag == null) useRag = true;
        }
    }
}
