package fr.spectra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mapping optionnel des messages Kafka JSON : extrait un champ comme contenu à indexer et
 * recopie des champs choisis dans les métadonnées (filtrage / traçabilité au retrieval).
 *
 * <p>Par défaut (aucun {@code contentField} configuré), le payload est indexé brut. Quand un
 * champ de contenu est configuré, seul ce champ est indexé — utile quand un message est un
 * événement structuré dont un seul champ (ex. {@code body}) porte le texte pertinent.</p>
 *
 * <p>Les champs sont désignés par nom simple ({@code "body"}) ou par pointeur JSON
 * ({@code "/data/text"}, RFC 6901).</p>
 */
public class KafkaPayloadMapper {

    private static final Logger log = LoggerFactory.getLogger(KafkaPayloadMapper.class);

    private final ObjectMapper objectMapper;

    public KafkaPayloadMapper() {
        this(new ObjectMapper());
    }

    public KafkaPayloadMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Résultat du mapping : contenu à indexer + métadonnées extraites. */
    public record Mapped(byte[] content, Map<String, String> metadata) {}

    /**
     * @param value        payload brut du message
     * @param contentField champ de contenu (null/blank → payload brut inchangé)
     * @param metadataFields champs à recopier en métadonnées (ignorés si contentField null)
     */
    public Mapped map(byte[] value, String contentField, List<String> metadataFields) {
        // Payload brut : aucun mapping, aucune métadonnée extraite.
        if (contentField == null || contentField.isBlank() || value == null || value.length == 0) {
            return new Mapped(value != null ? value : new byte[0], Map.of());
        }
        try {
            JsonNode root = objectMapper.readTree(value);
            JsonNode contentNode = at(root, contentField);
            String text = nodeToText(contentNode);

            Map<String, String> metadata = new HashMap<>();
            if (metadataFields != null) {
                for (String field : metadataFields) {
                    JsonNode n = at(root, field);
                    if (!n.isMissingNode() && !n.isNull()) {
                        metadata.put(leafName(field), nodeToText(n));
                    }
                }
            }
            return new Mapped(text.getBytes(StandardCharsets.UTF_8), metadata);
        } catch (Exception e) {
            // JSON invalide alors qu'un mapping est demandé : on retombe sur le payload brut
            // plutôt que d'échouer (le message n'est pas « empoisonné » pour autant).
            log.warn("Mapping JSON impossible (contentField='{}') : {} — payload brut utilisé",
                    contentField, e.getMessage());
            return new Mapped(value, Map.of());
        }
    }

    private JsonNode at(JsonNode root, String field) {
        return field.startsWith("/") ? root.at(field) : root.path(field);
    }

    private String nodeToText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        return node.isValueNode() ? node.asText() : node.toString();
    }

    /** Dernier segment d'un pointeur JSON, ou le nom tel quel. */
    private String leafName(String field) {
        String f = field.startsWith("/") ? field.substring(1) : field;
        int slash = f.lastIndexOf('/');
        return slash >= 0 ? f.substring(slash + 1) : f;
    }
}
