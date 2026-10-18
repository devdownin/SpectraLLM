package fr.spectra.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Calibration par grille : chaque combinaison des valeurs données devient un bras d'ablation RAG,
 * mesuré sur le même benchmark. Une liste vide ou absente laisse le réglage à sa configuration.
 *
 * @param rerankMinScores  seuils d'abstention du reranker à essayer (ex. 0, 0.1, 0.2, 0.3)
 * @param neighborWindows  fenêtres d'expansion aux voisins (ex. 0, 1)
 * @param dedupStrategies  {@code none}, {@code jaccard}, {@code mmr}
 */
public record CalibrationRequest(List<Float> rerankMinScores, List<Integer> neighborWindows,
                                 List<String> dedupStrategies, Integer maxContextChunks, Integer runs) {

    /** Nombre maximal de bras : au-delà, une passe sur CPU dure des heures. */
    public static final int MAX_ARMS = 16;

    public AblationRequest toAblationRequest() {
        List<AblationRequest.Arm> arms = new ArrayList<>();
        for (Float minScore : orNull(rerankMinScores)) {
            for (Integer window : orNull(neighborWindows)) {
                for (String dedup : orNull(dedupStrategies)) {
                    arms.add(new AblationRequest.Arm(label(minScore, window, dedup), null, true,
                            RagOverrides.NONE.withTuning(minScore, window, dedup)));
                }
            }
        }
        if (arms.size() > MAX_ARMS) {
            throw new IllegalArgumentException("Grille de " + arms.size() + " combinaisons : " + MAX_ARMS
                    + " au plus (réduisez le nombre de valeurs par réglage).");
        }
        return new AblationRequest(arms, maxContextChunks, runs);
    }

    private static <T> List<T> orNull(List<T> values) {
        if (values == null || values.isEmpty()) {
            List<T> unset = new ArrayList<>();
            unset.add(null);
            return unset;
        }
        return values;
    }

    private static String label(Float minScore, Integer window, String dedup) {
        return "minScore=" + (minScore != null ? minScore : "config")
                + " · voisins=" + (window != null ? window : "config")
                + " · dédup=" + (dedup != null ? dedup : "config");
    }
}
