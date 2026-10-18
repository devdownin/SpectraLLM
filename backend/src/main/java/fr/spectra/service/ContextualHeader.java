package fr.spectra.service;

import java.util.Map;

/**
 * En-tête contextuel d'un chunk, préfixé au texte <b>embeddé</b> — jamais au texte stocké, cité
 * ou indexé en BM25.
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

    private static String value(Map<String, ?> metadata, String key) {
        Object v = metadata.get(key);
        return v != null && !v.toString().isBlank() ? v.toString().strip() : null;
    }
}
