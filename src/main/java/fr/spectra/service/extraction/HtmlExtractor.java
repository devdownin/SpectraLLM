package fr.spectra.service.extraction;

import fr.spectra.model.ExtractedDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
        String html;
        try {
            html = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ExtractionException("Impossible de lire le contenu HTML: " + e.getMessage());
        }
        Document doc = Jsoup.parse(html);

        // Supprimer les éléments non-contenu
        doc.select("script, style, nav, footer, header, aside, " +
                   "[role=navigation], [role=banner], [role=complementary], " +
                   ".nav, .menu, .sidebar, .footer, .header, .advertisement, .cookie-banner").remove();

        String title = doc.title();

        // Extraire le texte des éléments de contenu en préservant la structure
        StringBuilder text = new StringBuilder();
        if (title != null && !title.isBlank()) {
            text.append(title).append("\n\n");
        }

        for (Element el : doc.select("h1, h2, h3, h4, h5, h6, p, li, td, th, blockquote, pre, dt, dd, figcaption")) {
            String elText = el.text().strip();
            if (!elText.isBlank()) {
                text.append(elText).append("\n");
            }
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
}
