package fr.spectra.service.extraction;

import fr.spectra.model.ExtractedDocument;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Extracteur pour les fichiers Word 97-2003 (.doc) via Apache POI HWPF.
 */
@Component
public class DocExtractor implements DocumentExtractor {

    @Override
    public Set<String> supportedContentTypes() {
        return Set.of("application/msword");
    }

    @Override
    public ExtractedDocument extract(String fileName, InputStream content) throws ExtractionException {
        try (HWPFDocument document = new HWPFDocument(content);
             WordExtractor extractor = new WordExtractor(document)) {

            Map<String, String> metadata = new HashMap<>();
            metadata.put("format", "DOC");

            var summaryInfo = document.getSummaryInformation();
            if (summaryInfo != null) {
                if (summaryInfo.getTitle() != null) metadata.put("title", summaryInfo.getTitle());
                if (summaryInfo.getAuthor() != null) metadata.put("author", summaryInfo.getAuthor());
            }

            String[] paragraphs = extractor.getParagraphText();
            StringBuilder text = new StringBuilder();
            for (String para : paragraphs) {
                String trimmed = para.trim();
                if (!trimmed.isEmpty()) {
                    text.append(trimmed).append("\n");
                }
            }

            return new ExtractedDocument(fileName, "application/msword", text.toString(), 1, metadata);
        } catch (Exception e) {
            throw new ExtractionException("Erreur extraction DOC: " + fileName, e);
        }
    }
}
