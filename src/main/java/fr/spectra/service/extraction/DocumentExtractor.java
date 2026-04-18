package fr.spectra.service.extraction;

import fr.spectra.model.ExtractedDocument;

import java.io.InputStream;
import java.util.Set;

/**
 * Interface commune pour l'extraction de texte depuis un document.
 */
public interface DocumentExtractor {

    /**
     * Types MIME supportés par cet extracteur.
     */
    Set<String> supportedContentTypes();

    /**
     * Extrait le texte brut et les métadonnées d'un document.
     */
    ExtractedDocument extract(String fileName, InputStream content) throws ExtractionException;
}
