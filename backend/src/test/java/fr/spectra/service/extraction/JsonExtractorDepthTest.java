package fr.spectra.service.extraction;

import fr.spectra.model.ExtractedDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Borne de profondeur de l'aplatissement JSON : un document pathologiquement imbriqué
 * ne doit ni faire déborder la pile ni perdre son contenu (sérialisé en bloc au-delà
 * de la limite).
 */
class JsonExtractorDepthTest {

    private final JsonExtractor extractor = new JsonExtractor();

    private static byte[] nestedJson(int depth, String leafValue) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"a\":".repeat(depth));
        sb.append("\"").append(leafValue).append("\"");
        sb.append("}".repeat(depth));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void extract_deeplyNestedJson_noStackOverflowAndKeepsContent() throws Exception {
        // 400 niveaux : bien au-delà de MAX_FLATTEN_DEPTH (128), sous la limite Jackson (1000).
        ExtractedDocument doc = extractor.extract("deep.json",
                new ByteArrayInputStream(nestedJson(400, "valeur-feuille")));

        // Le contenu au-delà de la borne reste indexé (sous-arbre sérialisé en bloc).
        assertThat(doc.text()).contains("valeur-feuille");
    }

    @Test
    void extract_normalDepth_stillFlattensKeyByKey() throws Exception {
        ExtractedDocument doc = extractor.extract("flat.json",
                new ByteArrayInputStream("{\"nom\":\"Spectra\",\"detail\":{\"type\":\"RAG\"}}"
                        .getBytes(StandardCharsets.UTF_8)));

        assertThat(doc.text()).contains("nom: Spectra");
        assertThat(doc.text()).contains("detail.type: RAG");
    }
}
