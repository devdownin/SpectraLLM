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

    /**
     * Profondeur maximale d'aplatissement. Au-delà, le sous-arbre est sérialisé tel quel
     * (tronqué) au lieu de poursuivre la récursion : borne explicite contre le
     * StackOverflowError sur un JSON pathologiquement imbriqué, indépendante des
     * {@code StreamReadConstraints} de la version de Jackson embarquée.
     */
    private static final int MAX_FLATTEN_DEPTH = 128;
    /** Taille max du texte conservé pour un sous-arbre trop profond. */
    private static final int MAX_SUBTREE_CHARS = 4_096;

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

            flattenJson(root, "", text, 0);

            if (root.isArray()) {
                metadata.put("elementCount", String.valueOf(root.size()));
            }

            return new ExtractedDocument(fileName, "application/json", text.toString(), 1, metadata);
        } catch (Exception e) {
            throw new ExtractionException("Erreur extraction JSON: " + fileName, e);
        }
    }

    /**
     * Aplatit récursivement un arbre JSON en texte lisible (profondeur bornée par
     * {@link #MAX_FLATTEN_DEPTH} — le contenu au-delà reste indexé, en bloc).
     */
    private void flattenJson(JsonNode node, String prefix, StringBuilder sb, int depth) {
        if (depth >= MAX_FLATTEN_DEPTH) {
            String raw = node.toString();
            if (raw.length() > MAX_SUBTREE_CHARS) {
                raw = raw.substring(0, MAX_SUBTREE_CHARS) + "…";
            }
            sb.append(prefix).append(": ").append(raw).append("\n\n");
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
                flattenJson(field.getValue(), key, sb, depth + 1);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flattenJson(node.get(i), prefix + "[" + i + "]", sb, depth + 1);
            }
        } else {
            sb.append(prefix).append(": ").append(node.asText()).append("\n\n");
        }
    }
}
