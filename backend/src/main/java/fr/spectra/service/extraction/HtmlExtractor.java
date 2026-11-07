package fr.spectra.service.extraction;

import fr.spectra.model.ExtractedDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracteur de texte pour les pages HTML.
 * Utilise jsoup pour parser le HTML et extraire le contenu textuel pertinent,
 * en éliminant les éléments de navigation, publicités, scripts et styles.
 */
@Component
public class HtmlExtractor implements DocumentExtractor {

    @Override
    public Set<String> supportedContentTypes() {
        return Set.of("text/html");
    }

    @Override
    public ExtractedDocument extract(String fileName, InputStream inputStream) throws ExtractionException {
        Document doc;
        try {
            // charset=null : jsoup auto-détecte l'encodage via le BOM, la balise
            // <meta charset> ou <meta http-equiv> — évite le mojibake sur les pages
            // non-UTF-8 (ISO-8859-1, windows-1252, fréquentes en contenu FR).
            doc = Jsoup.parse(inputStream, null, fileName != null ? fileName : "");
        } catch (Exception e) {
            throw new ExtractionException("Impossible de lire le contenu HTML: " + e.getMessage());
        }

        // Supprimer les éléments non-contenu
        doc.select("script, style, nav, footer, body > header, aside, " +
                   "[role=navigation], [role=banner], [role=complementary], " +
                   ".nav, .menu, .sidebar, .footer, .header, .advertisement, .cookie-banner").remove();

        String title = doc.title();

        // Extraire le texte des éléments de contenu en préservant la structure
        StringBuilder text = new StringBuilder();
        if (title != null && !title.isBlank()) {
            text.append(title).append("\n\n");
        }

        for (Element el : doc.select("h1, h2, h3, h4, h5, h6, p, li, table, blockquote, pre, dt, dd, figcaption")) {
            if (el.parents().stream().anyMatch(parent -> parent.normalName().equals("table"))) continue;
            String elText = el.text().strip();
            if (elText.isBlank()) continue;
            String tag = el.normalName();
            if (tag.matches("h[1-6]")) {
                text.append("#".repeat(tag.charAt(1) - '0')).append(' ');
            }
            if ("table".equals(tag)) appendTable(text, el);
            else text.append(elText).append("\n\n");
        }

        // Fallback si aucun contenu structuré n'a été trouvé
        if (text.length() <= (title != null ? title.length() + 2 : 0)) {
            String bodyText = doc.body() != null ? doc.body().text().strip() : "";
            if (!bodyText.isBlank()) {
                text = new StringBuilder(bodyText);
            }
        }

        Map<String, String> metadata = Map.of(
                "source", fileName,
                "contentType", "text/html",
                "title", title != null ? title : ""
        );

        return new ExtractedDocument(fileName, "text/html", text.toString().strip(), 1, metadata, Instant.now());
    }
    private static void appendTable(StringBuilder text, Element table) {
        Element caption = table.children().stream().filter(child -> child.normalName().equals("caption")).findFirst().orElse(null);
        if (caption != null && !caption.text().isBlank()) {
            text.append("Table: ").append(caption.text()).append('\n');
        }
        List<Element> rows = table.select("tr").stream()
                .filter(row -> row.closest("table") == table).toList();
        if (rows.isEmpty()) return;
        // Simple headered tables map directly to Markdown. Preserve the text of complex layouts.
        List<String> headers = cells(rows.getFirst());
        boolean regular = !headers.isEmpty() && rows.getFirst().children().stream().allMatch(cell -> cell.normalName().equals("th"))
                && rows.stream().allMatch(row -> cells(row).size() == headers.size())
                && table.select("[rowspan]:not([rowspan=1]), [colspan]:not([colspan=1])").isEmpty();
        if (!regular) {
            for (Element row : rows) text.append(String.join(" | ", cells(row))).append('\n');
            text.append('\n');
            return;
        }
        appendRow(text, headers);
        appendRow(text, java.util.Collections.nCopies(headers.size(), "---"));
        for (Element row : rows.subList(1, rows.size())) appendRow(text, cells(row));
        text.append('\n');
    }

    private static List<String> cells(Element row) {
        List<String> values = new ArrayList<>();
        for (Element cell : row.children()) {
            if (cell.normalName().equals("th") || cell.normalName().equals("td")) {
                values.add(cell.text().replace("|", "\\|"));
            }
        }
        return values;
    }

    private static void appendRow(StringBuilder text, List<String> cells) {
        text.append("| ").append(String.join(" | ", cells)).append(" |\n");
    }
}
