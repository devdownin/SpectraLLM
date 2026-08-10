package fr.spectra.util;

/**
 * Extraction du JSON noyé dans une réponse de LLM.
 *
 * <p>Un modèle à qui l'on demande du JSON répond volontiers par une clôture Markdown, une
 * phrase d'introduction, ou les deux. Cette classe isole l'objet.
 *
 * <p>Existait en double, à l'identique, dans {@code EvaluationService} et
 * {@code QualityBenchmarkService} — la seule différence étant une espace. Deux copies d'une
 * fonction pure, c'est une correction sur deux qui se perd. Voir
 * {@code docs/process/audit-simplification.fr.md} (S3).
 */
public final class LlmJson {

    private LlmJson() {}

    /**
     * Renvoie le premier objet JSON complet trouvé dans {@code text}, ou {@code null} si le
     * texte n'en contient pas d'exploitable.
     *
     * <p>Volontairement non strict : on cherche du {@code {} au {@code }} le plus externe, sans
     * analyser la syntaxe. C'est l'appelant qui désérialise et décide si le résultat tient.
     */
    public static String extract(String text) {
        if (text == null || text.isBlank()) return null;
        String clean = text.replaceAll("```json|```", "").trim();
        int start = clean.indexOf('{');
        int end = clean.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) return null;
        return clean.substring(start, end + 1);
    }
}
