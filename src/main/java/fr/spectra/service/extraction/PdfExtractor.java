package fr.spectra.service.extraction;

import fr.spectra.model.ExtractedDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Basic PDF extractor using Apache PDFBox.
 * Active only when {@code spectra.layout-parser.enabled} is false or absent.
 * When layout-parser is enabled, {@link LayoutAwarePdfExtractor} takes over.
 */
@Component
@ConditionalOnProperty(
        prefix = "spectra.layout-parser", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class PdfExtractor implements DocumentExtractor {

    @Override
    public Set<String> supportedContentTypes() {
        return Set.of("application/pdf");
    }

    @Override
    public ExtractedDocument extract(String fileName, InputStream content) throws ExtractionException {
        try (PDDocument document = Loader.loadPDF(content.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);

            int pageCount = document.getNumberOfPages();
            Map<String, String> metadata = extractMetadata(document.getDocumentInformation());
            metadata.put("format", "PDF");

            return new ExtractedDocument(fileName, "application/pdf", text, pageCount, metadata);
        } catch (Exception e) {
            throw new ExtractionException("Erreur extraction PDF: " + fileName, e);
        }
    }

    private Map<String, String> extractMetadata(PDDocumentInformation info) {
        Map<String, String> meta = new HashMap<>();
        if (info.getTitle() != null) meta.put("title", info.getTitle());
        if (info.getAuthor() != null) meta.put("author", info.getAuthor());
        if (info.getSubject() != null) meta.put("subject", info.getSubject());
        if (info.getCreationDate() != null) meta.put("creationDate", info.getCreationDate().getTime().toString());
        return meta;
    }
}
