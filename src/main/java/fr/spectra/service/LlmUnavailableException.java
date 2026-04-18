package fr.spectra.service;

/**
 * Levée par le circuit breaker LLM lorsque le modèle de langage est
 * temporairement indisponible (circuit ouvert ou quota d'échecs dépassé).
 *
 * <p>Mappée en HTTP 503 Service Unavailable par {@code GlobalExceptionHandler}.
 */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
