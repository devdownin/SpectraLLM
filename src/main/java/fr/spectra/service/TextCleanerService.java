package fr.spectra.service;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Nettoyage et normalisation du texte extrait.
 */
@Service
public class TextCleanerService {

    private static final Pattern MULTIPLE_NEWLINES = Pattern.compile("\\n{3,}");
    private static final Pattern MULTIPLE_SPACES = Pattern.compile(" {2,}");
    private static final Pattern PAGE_MARKERS = Pattern.compile("(?m)^\\s*-\\s*\\d+\\s*-\\s*$");
    private static final Pattern HEADER_FOOTER = Pattern.compile("(?m)^(Page \\d+(/\\d+)?|Confidentiel|CONFIDENTIEL|©.*)$");
    private static final Pattern TABLE_BORDERS = Pattern.compile("\\s*\\|\\s*");
    // Détecte une ligne de séparation Markdown (| --- |) — présence = contenu Markdown, ne pas toucher aux pipes
    private static final Pattern MARKDOWN_TABLE_SEP = Pattern.compile("\\|\\s*-{2,}");
    private static final Pattern BULLET_POINTS = Pattern.compile("[•●■◦▪▸►]");

    // Artefacts OCR courants
    private static final Pattern OCR_LIGATURES = Pattern.compile("[ﬀﬁﬂﬃﬄ]");

    public String clean(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String result = text;

        // 1. Normalisation Unicode NFC
        result = Normalizer.normalize(result, Normalizer.Form.NFC);

        // 2. Remplacement des ligatures OCR
        result = replaceOcrLigatures(result);

        // 3. Suppression des marqueurs de page
        result = PAGE_MARKERS.matcher(result).replaceAll("");

        // 4. Suppression des en-têtes/pieds de page récurrents
        result = HEADER_FOOTER.matcher(result).replaceAll("");

        // 5. Normalisation des séparateurs de tableau (ignoré si contenu Markdown structuré)
        if (!MARKDOWN_TABLE_SEP.matcher(result).find()) {
            result = TABLE_BORDERS.matcher(result).replaceAll(" ");
        }

        // 6. Normalisation des puces
        result = BULLET_POINTS.matcher(result).replaceAll("-");

        // 7. Normalisation des espaces
        result = MULTIPLE_SPACES.matcher(result).replaceAll(" ");
        result = MULTIPLE_NEWLINES.matcher(result).replaceAll("\n\n");

        return result.strip();
    }

    private String replaceOcrLigatures(String text) {
        return text
                .replace("ﬀ", "ff")
                .replace("ﬁ", "fi")
                .replace("ﬂ", "fl")
                .replace("ﬃ", "ffi")
                .replace("ﬄ", "ffl");
    }
}
