package fr.spectra.service.extraction;

/**
 * Erreur d'extraction — format non géré ou fichier illisible/corrompu.
 *
 * <p>Non vérifiée ({@link RuntimeException}) : un échec d'extraction sur <i>un</i> fichier
 * remonte au pipeline d'ingestion, qui marque la tâche en erreur sans interrompre le traitement
 * des autres fichiers (utile lors de l'ingestion d'une archive ZIP entière).</p>
 */
public class ExtractionException extends RuntimeException {

    public ExtractionException(String message) {
        super(message);
    }

    public ExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
