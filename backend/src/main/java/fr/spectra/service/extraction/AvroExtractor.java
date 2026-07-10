package fr.spectra.service.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.model.ExtractedDocument;
import org.apache.avro.file.DataFileStream;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumReader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Extracteur pour les fichiers Apache Avro (.avro).
 *
 * <p>Lit le schéma embarqué dans le fichier, désérialise chaque enregistrement
 * en JSON puis concatène les textes pour produire un {@link ExtractedDocument}
 * traitable par le pipeline de chunking standard.</p>
 */
@Component
public class AvroExtractor implements DocumentExtractor {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public Set<String> supportedContentTypes() {
        return Set.of("application/avro");
    }

    @Override
    public ExtractedDocument extract(String fileName, InputStream content) throws ExtractionException {
        try {
            DatumReader<GenericRecord> datumReader = new GenericDatumReader<>();
            StringBuilder text = new StringBuilder();
            Map<String, String> metadata = new HashMap<>();
            metadata.put("format", "AVRO");
            int recordCount = 0;

            try (DataFileStream<GenericRecord> stream = new DataFileStream<>(content, datumReader)) {
                // Métadonnées du schéma
                String schema = stream.getSchema().getName();
                metadata.put("avroSchema", schema);

                for (GenericRecord record : stream) {
                    // Chaque enregistrement est converti en JSON, puis ajouté au texte
                    String json = record.toString();
                    text.append(json).append("\n");
                    recordCount++;
                }
            }

            metadata.put("recordCount", String.valueOf(recordCount));
            return new ExtractedDocument(fileName, "application/avro", text.toString(), recordCount, metadata);
        } catch (Exception e) {
            throw new ExtractionException("Erreur extraction AVRO: " + fileName, e);
        }
    }
}
