package fr.spectra.service.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.model.ExtractedDocument;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Extracteur JSON — via Jackson ({@code ObjectMapper} / {@code JsonNode}).
 *
 * <p>Un JSON n'est pas de la prose : on parcourt récursivement l'arbre pour en extraire les
 * valeurs textuelles (en aplatissant objets et tableaux), ce qui donne un texte indexable tout
 * en préservant le contenu utile. Ainsi un fichier de données structurées devient interrogeable
 * par le RAG comme n'importe quel document.</p>
 */
@Component
public class JsonExtractor implements DocumentExtractor {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Set<String> supportedContentTypes() {
        return Set.of("application/json");
    }

    @Override
    public ExtractedDocument extract(String fileName, InputStream content) throws ExtractionException {
        try {
            JsonNode root = objectMapper.readTree(content);
            StringBuilder text = new StringBuilder();
            Map<String, String> metadata = new HashMap<>();
            metadata.put("format", "JSON");

            flattenJson(root, "", text);

            if (root.isArray()) {
                metadata.put("elementCount", String.valueOf(root.size()));
            }

            return new ExtractedDocument(fileName, "application/json", text.toString(), 1, metadata);
        } catch (Exception e) {
            throw new ExtractionException("Erreur extraction JSON: " + fileName, e);
        }
    }

    /**
     * Aplatit récursivement un arbre JSON en texte lisible.
     */
    private void flattenJson(JsonNode node, String prefix, StringBuilder sb) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
                flattenJson(field.getValue(), key, sb);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flattenJson(node.get(i), prefix + "[" + i + "]", sb);
            }
        } else {
            sb.append(prefix).append(": ").append(node.asText()).append("\n\n");
        }
    }
}
