package fr.spectra.util;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mesures de similarité entre deux textes.
 *
 * <p>Existait en double, à l'identique, dans {@code ArticleCommentService} et
 * {@code DpoGenerationService}. Voir {@code docs/process/audit-simplification.fr.md} (S3).
 */
public final class TextSimilarity {

    private TextSimilarity() {}

    /**
     * Indice de Jaccard sur les mots, insensible à la casse.
     *
     * <p>Deux textes vides sont réputés identiques ({@code 1.0}) : c'est le cas dégénéré, et le
     * traiter comme une divergence ferait remonter de faux positifs côté appelants, qui s'en
     * servent pour écarter des doublons.
     */
    public static double jaccard(String a, String b) {
        Set<String> setA = Arrays.stream(a.toLowerCase().split("\\s+")).collect(Collectors.toSet());
        Set<String> setB = Arrays.stream(b.toLowerCase().split("\\s+")).collect(Collectors.toSet());
        if (setA.isEmpty() && setB.isEmpty()) return 1.0;
        long intersection = setA.stream().filter(setB::contains).count();
        long union = setA.size() + setB.size() - intersection;
        return union == 0 ? 1.0 : (double) intersection / union;
    }

    /** Similarité cosinus de deux vecteurs ; 0 si l'un est nul ou si les dimensions diffèrent. */
    public static double cosine(java.util.List<Float> a, java.util.List<Float> b) {
        if (a == null || b == null || a.size() != b.size() || a.isEmpty()) return 0.0;
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            double x = a.get(i);
            double y = b.get(i);
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        return normA == 0.0 || normB == 0.0 ? 0.0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
