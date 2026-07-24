package fr.spectra.service.extraction;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aiguilleur des extracteurs — du fichier vers la bonne {@link DocumentExtractor}.
 *
 * <p>Au démarrage, Spring injecte <b>toutes</b> les implémentations de {@link DocumentExtractor}
 * (par {@code List<DocumentExtractor>}). La factory construit alors une table
 * {@code content-type → extracteur} en demandant à chacune les types qu'elle gère. À l'exécution,
 * {@link #resolveContentType(String)} déduit le type MIME depuis l'extension, puis
 * {@link #getExtractor(String)} renvoie l'extracteur correspondant (ou une
 * {@link ExtractionException} si le format est inconnu).</p>
 *
 * <p>Conséquence pratique : ce code n'a <b>aucune liste codée en dur</b> d'extracteurs. Un
 * nouveau format devient disponible dès qu'on déclare son extracteur comme bean — aucune
 * modification ici.</p>
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
        if (lower.endsWith(".avro")) return "application/avro";
        // Markdown et CSV : contenu textuel, routé vers l'extracteur texte (ils étaient
        // rejetés en upload direct et silencieusement ignorés dans les ZIP).
        if (lower.endsWith(".txt") || lower.endsWith(".md")
                || lower.endsWith(".markdown") || lower.endsWith(".csv")) return "text/plain";
        if (lower.endsWith(".zip")) return "application/zip";
        throw new ExtractionException("Extension de fichier non supportée: " + fileName);
    }
}
