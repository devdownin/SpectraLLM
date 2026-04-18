package fr.spectra.service.extraction;

import fr.spectra.model.ExtractedDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests unitaires de DocxExtractor.
 * Les fichiers DOCX sont générés programmatiquement via Apache POI.
 */
class DocxExtractorTest {

    private final DocxExtractor extractor = new DocxExtractor();

    // ── supportedContentTypes ─────────────────────────────────────────────────

    @Test
    void supportedContentTypes_containsDocxMimeType() {
        assertThat(extractor.supportedContentTypes())
                .contains("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    // ── Extraction de base ────────────────────────────────────────────────────

    @Test
    void extract_singleParagraph_textIncluded() throws Exception {
        byte[] docx = buildDocx(doc -> {
            XWPFParagraph para = doc.createParagraph();
            para.createRun().setText("Paragraphe de test");
        });

        ExtractedDocument result = extractor.extract("test.docx", stream(docx));

        assertThat(result.text()).contains("Paragraphe de test");
    }

    @Test
    void extract_multipleParagraphs_allIncluded() throws Exception {
        byte[] docx = buildDocx(doc -> {
            addParagraph(doc, "Premier paragraphe");
            addParagraph(doc, "Deuxième paragraphe");
            addParagraph(doc, "Troisième paragraphe");
        });

        ExtractedDocument result = extractor.extract("multi.docx", stream(docx));

        assertThat(result.text()).contains("Premier paragraphe");
        assertThat(result.text()).contains("Deuxième paragraphe");
        assertThat(result.text()).contains("Troisième paragraphe");
    }

    @Test
    void extract_emptyParagraphsSkipped() throws Exception {
        byte[] docx = buildDocx(doc -> {
            doc.createParagraph(); // vide
            addParagraph(doc, "Contenu réel");
            doc.createParagraph(); // vide
        });

        ExtractedDocument result = extractor.extract("sparse.docx", stream(docx));

        assertThat(result.text().trim()).isNotEmpty();
        assertThat(result.text()).contains("Contenu réel");
    }

    // ── Métadonnées ───────────────────────────────────────────────────────────

    @Test
    void extract_metadataFormatIsDocx() throws Exception {
        byte[] docx = buildDocx(doc -> addParagraph(doc, "Texte"));

        ExtractedDocument result = extractor.extract("doc.docx", stream(docx));

        assertThat(result.metadata()).containsEntry("format", "DOCX");
    }

    @Test
    void extract_sourceFilePreserved() throws Exception {
        byte[] docx = buildDocx(doc -> addParagraph(doc, "Texte"));

        ExtractedDocument result = extractor.extract("mon_document.docx", stream(docx));

        assertThat(result.sourceFile()).isEqualTo("mon_document.docx");
    }

    @Test
    void extract_pageCountIsOne() throws Exception {
        byte[] docx = buildDocx(doc -> addParagraph(doc, "Texte"));

        ExtractedDocument result = extractor.extract("doc.docx", stream(docx));

        assertThat(result.pageCount()).isEqualTo(1);
    }

    // ── Tableau ───────────────────────────────────────────────────────────────

    @Test
    void extract_tableContent_includedInText() throws Exception {
        byte[] docx = buildDocx(doc -> {
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("En-tête 1");
            table.getRow(0).getCell(1).setText("En-tête 2");
            table.getRow(1).getCell(0).setText("Valeur A");
            table.getRow(1).getCell(1).setText("Valeur B");
        });

        ExtractedDocument result = extractor.extract("table.docx", stream(docx));

        assertThat(result.text()).contains("En-tête 1");
        assertThat(result.text()).contains("Valeur A");
    }

    // ── Erreur ────────────────────────────────────────────────────────────────

    @Test
    void extract_invalidDocx_throwsExtractionException() {
        byte[] garbage = "not a docx file at all".getBytes();
        assertThatThrownBy(() -> extractor.extract("bad.docx", stream(garbage)))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("bad.docx");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @FunctionalInterface
    interface DocxBuilder {
        void build(XWPFDocument doc) throws Exception;
    }

    private byte[] buildDocx(DocxBuilder builder) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            builder.build(doc);
            doc.write(baos);
        }
        return baos.toByteArray();
    }

    private void addParagraph(XWPFDocument doc, String text) {
        XWPFParagraph para = doc.createParagraph();
        XWPFRun run = para.createRun();
        run.setText(text);
    }

    private ByteArrayInputStream stream(byte[] data) {
        return new ByteArrayInputStream(data);
    }
}
