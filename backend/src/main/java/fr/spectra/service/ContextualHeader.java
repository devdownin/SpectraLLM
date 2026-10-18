package fr.spectra.service;

import java.util.Map;

/**
 * En-tête contextuel d'un chunk, préfixé au texte <b>embeddé</b> — jamais au texte stocké ni
 * cité. Le BM25 en indexe les termes ({@link #terms}) dans un champ séparé du texte.
 *
 * <p>Version déterministe du <i>Contextual Retrieval</i> : un chunk isolé perd souvent son sujet
 * (« la valeur par défaut est 512 » — de quoi ?). Le titre du document et le chemin de sections
 * Markdown le lui rendent, sans appel LLM par chunk. Rien n'est ajouté côté requête : des chunks
 * avec et sans en-tête cohabitent sans incohérence d'espace vectoriel, donc aucune ré-indexation
 * n'est imposée — seuls les chunks indexés ou ré-indexés ensuite en bénéficient.</p>
 */
public final class ContextualHeader {

    private ContextualHeader() {}

    /** Texte à embedder pour un chunk : en-tête (s'il y a matière) puis texte. */
    public static String apply(String text, Map<String, ?> metadata) {
        String header = header(metadata);
        return header.isEmpty() ? text : header + text;
    }

    static String header(Map<String, ?> metadata) {
        if (metadata == null) return "";
        String title = value(metadata, "title");
        String source = value(metadata, "sourceFile");
        String section = value(metadata, "section");
        String document = title != null && source != null && !title.equals(source) ? title + " — " + source
                : title != null ? title : source;
        StringBuilder header = new StringBuilder();
        if (document != null) header.append("Document : ").append(document).append('\n');
        if (section != null) header.append("Section : ").append(section).append('\n');
        return header.isEmpty() ? "" : header.append('\n').toString();
    }

    /**
     * Termes d'en-tête pour le champ dédié du BM25 : titre, nom de fichier découpé en mots
     * ({@code contrat_cadre-2026.pdf} → {@code contrat cadre-2026 pdf}), section.
     * {@code null} si le chunk n'en porte aucun.
     */
    public static String terms(Map<String, ?> metadata) {
        if (metadata == null) return null;
        StringBuilder terms = new StringBuilder();
        String title = value(metadata, "title");
        String source = value(metadata, "sourceFile");
        String section = value(metadata, "section");
        if (title != null) terms.append(title).append(' ');
        if (source != null && !source.equals(title)) {
            terms.append(FILE_NAME_SEPARATORS.matcher(source).replaceAll(" ")).append(' ');
        }
        if (section != null) terms.append(section);
        String result = terms.toString().strip();
        return result.isEmpty() ? null : result;
    }

    private static final java.util.regex.Pattern FILE_NAME_SEPARATORS =
            java.util.regex.Pattern.compile("[._/\\\\]+");

    private static String value(Map<String, ?> metadata, String key) {
        Object v = metadata.get(key);
        return v != null && !v.toString().isBlank() ? v.toString().strip() : null;
    }
}
