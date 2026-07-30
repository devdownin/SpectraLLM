package fr.spectra.service.reranker;

import fr.spectra.service.RerankerClient.RankedResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Transformation des logits d'un cross-encoder en classement — la partie qui ne dépend d'aucun
 * modèle, et qu'on peut donc vérifier entièrement.
 *
 * <p>Isolée du moteur ONNX à dessein : l'inférence est une boîte noire tierce, mais le barème
 * appliqué et l'ordre produit décident seuls de ce que le RAG met dans son contexte. Ce sont eux
 * qui doivent être testés, pas la multiplication de matrices.
 */
public final class CrossEncoderScoring {

    private CrossEncoderScoring() {
    }

    /**
     * Barème appliqué au logit brut du modèle.
     *
     * <p><b>Ne change jamais l'ordre</b> — la sigmoïde est strictement croissante — mais change
     * l'échelle des scores publiés dans {@code rerankScores}, donc la comparabilité d'une
     * campagne de benchmark à l'autre.
     */
    public enum Activation {
        /** Reproduit {@code sentence-transformers} : scores dans ]0,1[. */
        SIGMOID {
            @Override
            public float apply(float logit) {
                return (float) (1.0 / (1.0 + Math.exp(-logit)));
            }
        },
        /** Logit brut, non borné et négatif la plupart du temps. */
        LOGIT {
            @Override
            public float apply(float logit) {
                return logit;
            }
        };

        public abstract float apply(float logit);

        /** Tolère {@code sigmoid}, {@code logit}, {@code identity}, {@code none} (insensible à la casse). */
        public static Activation parse(String value) {
            String normalized = value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
            return switch (normalized) {
                case "sigmoid" -> SIGMOID;
                case "logit", "identity", "none", "raw" -> LOGIT;
                default -> throw new IllegalArgumentException(
                        "Activation de reranking inconnue : '" + value
                                + "'. Valeurs acceptées : sigmoid, logit.");
            };
        }
    }

    /**
     * Classe les logits par score décroissant et retient les {@code topN} premiers.
     *
     * <p>À score égal, l'index le plus petit passe devant : sans ce départage, deux exécutions
     * pourraient produire un ordre différent sur le même modèle, et la comparaison à une
     * référence deviendrait ininterprétable.
     *
     * @param logits sorties brutes du modèle, dans l'ordre des passages soumis
     * @param topN   nombre de résultats voulus ; borné au nombre de passages
     */
    public static List<RankedResult> rank(float[] logits, int topN, Activation activation) {
        List<RankedResult> scored = new ArrayList<>(logits.length);
        for (int i = 0; i < logits.length; i++) {
            scored.add(new RankedResult(i, activation.apply(logits[i])));
        }
        scored.sort(Comparator.comparingDouble((RankedResult r) -> r.score()).reversed()
                .thenComparingInt(RankedResult::index));
        return List.copyOf(scored.subList(0, Math.min(topN, scored.size())));
    }
}
