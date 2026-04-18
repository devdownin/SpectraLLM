package fr.spectra.service.extraction;

import fr.spectra.model.ExtractedDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Component
public class DocxExtractor implements DocumentExtractor {

    @Override
    public Set<String> supportedContentTypes() {
        return Set.of(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        );
    }

    @Override
    public ExtractedDocument extract(String fileName, InputStream content) throws ExtractionException {
        try (XWPFDocument document = new XWPFDocument(content)) {
            StringBuilder text = new StringBuilder();
            Map<String, String> metadata = new HashMap<>();
            metadata.put("format", "DOCX");

            // Extraction des propriétés
            var coreProps = document.getProperties().getCoreProperties();
            if (coreProps.getTitle() != null) metadata.put("title", coreProps.getTitle());
            if (coreProps.getCreator() != null) metadata.put("author", coreProps.getCreator());

            // Extraction des paragraphes avec leur style
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String style = paragraph.getStyle();
                String paraText = paragraph.getText().trim();
                if (paraText.isEmpty()) continue;

                if (style != null && style.startsWith("Heading")) {
                    text.append("\n## ").append(paraText).append("\n\n");
                } else {
                    text.append(paraText).append("\n");
                }
            }

            // Extraction des tableaux
            for (XWPFTable table : document.getTables()) {
                text.append("\n");
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        text.append(cell.getText().trim()).append(" | ");
                    }
                    text.append("\n");
                }
                text.append("\n");
            }

            return new ExtractedDocument(fileName, "application/docx", text.toString(), 1, metadata);
        } catch (Exception e) {
            throw new ExtractionException("Erreur extraction DOCX: " + fileName, e);
        }
    }
}
