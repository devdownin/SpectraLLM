package fr.spectra.service.reranker;

import java.util.ArrayList;
import java.util.List;

/**
 * Comparaison d'un classement produit avec la référence capturée.
 *
 * <p><b>Ordre et scores sont rapportés séparément</b>, et c'est délibéré : seul l'ordre change
 * ce que le RAG met dans son contexte, tandis que l'échelle des scores dépend de l'activation
 * appliquée (sigmoïde côté {@code sentence-transformers}, logit brut côté runtime ONNX). Un
 * changement d'échelle assumé doit pouvoir être accepté sans renoncer au contrôle de l'ordre —
 * l'inverse (accepter une permutation parce que « les scores sont proches ») serait une
 * régression de qualité invisible.
 *
 * <p>Réserve honnête sur les ex æquo : deux passages à score identique peuvent s'échanger sans
 * que cela signifie quoi que ce soit. Le comparateur ne les traite pas spécialement, mais joint
 * les scores de référence au message d'écart, de sorte qu'un ex æquo se voit immédiatement à la
 * lecture de l'échec.
 */
final class RerankerParityCheck {

    /** Tolérance par défaut sur les scores — au-delà, ce n'est plus du bruit flottant. */
    static final double DEFAULT_SCORE_TOLERANCE = 1e-3;

    private RerankerParityCheck() {
    }

    /** Ce qui est vérifié sur les scores, en plus de l'ordre (toujours vérifié). */
    enum ScorePolicy {
        /** Les scores doivent coïncider à la tolérance près (même modèle, même activation). */
        COMPARE,
        /** L'échelle a changé volontairement : seul l'ordre est contrôlé. */
        IGNORE
    }

    /** Un écart constaté, formulé pour être lisible sans relancer d'outil. */
    record Divergence(String kind, String detail) {
        @Override
        public String toString() {
            return "[" + kind + "] " + detail;
        }
    }

    static List<Divergence> compare(RerankerParityReference reference,
                                    List<RerankerParityReference.QueryResults> actual,
                                    ScorePolicy scorePolicy,
                                    double scoreTolerance) {
        List<Divergence> divergences = new ArrayList<>();
        List<RerankerParityReference.QueryResults> expected = reference.queries();

        if (expected.size() != actual.size()) {
            divergences.add(new Divergence("nombre-de-requêtes",
                    "référence " + expected.size() + " requête(s), obtenu " + actual.size()));
            return divergences;
        }

        for (int q = 0; q < expected.size(); q++) {
            RerankerParityReference.QueryResults exp = expected.get(q);
            RerankerParityReference.QueryResults act = actual.get(q);

            if (!exp.query().equals(act.query())) {
                divergences.add(new Divergence("requête",
                        "position " + q + " : référence « " + truncate(exp.query())
                                + " », obtenu « " + truncate(act.query()) + " »"));
                continue;
            }

            List<RerankerParityReference.Ranked> expRanks = exp.results();
            List<RerankerParityReference.Ranked> actRanks = act.results();

            if (expRanks.size() != actRanks.size()) {
                divergences.add(new Divergence("nombre-de-résultats",
                        "« " + truncate(exp.query()) + " » : référence " + expRanks.size()
                                + " résultat(s), obtenu " + actRanks.size()));
                continue;
            }

            for (int r = 0; r < expRanks.size(); r++) {
                RerankerParityReference.Ranked expected1 = expRanks.get(r);
                RerankerParityReference.Ranked actual1 = actRanks.get(r);

                if (expected1.index() != actual1.index()) {
                    divergences.add(new Divergence("ordre",
                            "« " + truncate(exp.query()) + " » rang " + r + " : référence index "
                                    + expected1.index() + " (score " + fmt(expected1.score())
                                    + "), obtenu index " + actual1.index()
                                    + " (score " + fmt(actual1.score()) + ")"
                                    + " — scores de référence voisins : " + neighbours(expRanks, r)));
                    continue;
                }
                if (scorePolicy == ScorePolicy.COMPARE
                        && Math.abs(expected1.score() - actual1.score()) > scoreTolerance) {
                    divergences.add(new Divergence("score",
                            "« " + truncate(exp.query()) + " » rang " + r + " (index "
                                    + expected1.index() + ") : référence " + fmt(expected1.score())
                                    + ", obtenu " + fmt(actual1.score())
                                    + " (écart " + fmt(Math.abs(expected1.score() - actual1.score()))
                                    + " > tolérance " + fmt(scoreTolerance) + ")"));
                }
            }
        }
        return divergences;
    }

    /** Scores de référence autour d'un rang : rend un ex æquo visible dans le message d'échec. */
    private static String neighbours(List<RerankerParityReference.Ranked> ranks, int position) {
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, position - 1); i < Math.min(ranks.size(), position + 2); i++) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append("rang ").append(i).append('=').append(fmt(ranks.get(i).score()));
        }
        return sb.toString();
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    private static String truncate(String text) {
        return text.length() <= 50 ? text : text.substring(0, 47) + "…";
    }
}
