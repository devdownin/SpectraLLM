package fr.spectra.dto;

/**
 * Surcharges par requête des modules d'optimisation RAG, pour l'ablation A/B.
 *
 * <p>Chaque champ tri-état contrôle un module :</p>
 * <ul>
 *   <li>{@code null} — utilise le défaut de déploiement (présence du bean / config).</li>
 *   <li>{@code true} — force l'activation, <b>uniquement si le module est disponible</b> (un module
 *       désactivé par configuration ne peut pas être réactivé par requête).</li>
 *   <li>{@code false} — force la désactivation, quel que soit le déploiement.</li>
 * </ul>
 *
 * <p>Permet de mesurer l'apport marginal de chaque option en n'en changeant qu'une à la fois :
 * on déploie tout activé, puis on désactive (ou cumule) module par module via ces surcharges.</p>
 */
public record RagOverrides(
        Boolean adaptive,
        Boolean conversational,
        Boolean multiQuery,
        Boolean hybrid,
        Boolean rerank,
        Boolean corrective,
        Boolean compression,
        Boolean selfRag
) {
    /** Aucune surcharge : comportement de déploiement par défaut. */
    public static final RagOverrides NONE = new RagOverrides(null, null, null, null, null, null, null, null);

    /**
     * Résout l'état effectif d'un module : {@code null} → défaut ; sinon la surcharge, bornée par la
     * disponibilité (on ne peut pas activer un module absent).
     */
    public static boolean resolve(Boolean override, boolean present) {
        return override == null ? present : (override && present);
    }
}
