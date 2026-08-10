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
}
