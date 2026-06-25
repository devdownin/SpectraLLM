package fr.spectra.service.extraction;

import fr.spectra.model.ExtractedDocument;

import java.io.InputStream;
import java.util.Set;

/**
 * Contrat commun d'extraction de texte — un format, un extracteur (patron Strategy).
 *
 * <p>Chaque format de fichier (PDF, DOCX, HTML, JSON, XML, Avro…) a sa propre logique de
 * lecture. Plutôt qu'un gros {@code switch} central, chaque format implémente cette interface
 * et déclare les types MIME qu'il gère via {@link #supportedContentTypes()}. La
 * {@link DocumentExtractorFactory} les enregistre automatiquement et route chaque fichier vers
 * le bon extracteur. <b>Ajouter un format = ajouter une classe</b>, sans toucher au pipeline
 * d'ingestion — c'est le principe ouvert/fermé en action.</p>
 *
 * <p>Toutes les implémentations renvoient le même {@link fr.spectra.model.ExtractedDocument}
 * (texte brut + métadonnées), de sorte que la suite du pipeline est agnostique du format
 * d'origine.</p>
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
