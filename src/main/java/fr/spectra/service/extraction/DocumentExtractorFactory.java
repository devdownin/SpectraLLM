package fr.spectra.service.extraction;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Résout le bon extracteur à partir du content-type d'un fichier.
 */
@Component
public class DocumentExtractorFactory {

    private final Map<String, DocumentExtractor> extractorsByType;

    public DocumentExtractorFactory(List<DocumentExtractor> extractors) {
        this.extractorsByType = extractors.stream()
                .flatMap(e -> e.supportedContentTypes().stream().map(type -> Map.entry(type, e)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public DocumentExtractor getExtractor(String contentType) {
        DocumentExtractor extractor = extractorsByType.get(contentType);
        if (extractor == null) {
            throw new ExtractionException("Aucun extracteur disponible pour le type: " + contentType);
        }
        return extractor;
    }

    /**
     * Détecte le content-type à partir de l'extension du fichier.
     */
    public String resolveContentType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".txt")) return "text/plain";
        throw new ExtractionException("Extension de fichier non supportée: " + fileName);
    }
}
