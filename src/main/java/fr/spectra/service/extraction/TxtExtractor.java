package fr.spectra.service.extraction;

import fr.spectra.model.ExtractedDocument;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Extracteur simple pour les fichiers texte brut (.txt).
 */
@Component
public class TxtExtractor implements DocumentExtractor {

    @Override
    public Set<String> supportedContentTypes() {
        return Set.of("text/plain");
    }

    @Override
    public ExtractedDocument extract(String fileName, InputStream content) throws ExtractionException {
        try {
            String text = new BufferedReader(new InputStreamReader(content, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));
            return new ExtractedDocument(fileName, "text/plain", text, 1, Map.of("sourceFile", fileName));
        } catch (Exception e) {
            throw new ExtractionException("Erreur d'extraction TXT de " + fileName + ": " + e.getMessage());
        }
    }
}
