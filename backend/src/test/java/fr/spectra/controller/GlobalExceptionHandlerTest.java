package fr.spectra.controller;

import fr.spectra.service.extraction.ExtractionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires de GlobalExceptionHandler — mapping exceptions → ProblemDetail HTTP.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ── ExtractionException → 422 ─────────────────────────────────────────────

    @Test
    void handleExtraction_returns422() {
        ProblemDetail problem = handler.handleExtraction(
                new ExtractionException("Extraction échouée"));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
    }

    @Test
    void handleExtraction_titleIsSet() {
        ProblemDetail problem = handler.handleExtraction(
                new ExtractionException("err"));
        assertThat(problem.getTitle()).isEqualTo("Erreur d'extraction");
    }

    @Test
    void handleExtraction_detailContainsMessage() {
        ProblemDetail problem = handler.handleExtraction(
                new ExtractionException("Type non supporté: text/csv"));
        assertThat(problem.getDetail()).contains("Type non supporté: text/csv");
    }

    @Test
    void handleExtraction_withCause_detailFromMessage() {
        ExtractionException ex = new ExtractionException("Fichier corrompu",
                new RuntimeException("cause sous-jacente"));
        ProblemDetail problem = handler.handleExtraction(ex);
        assertThat(problem.getDetail()).contains("Fichier corrompu");
    }

    // ── Exception générique → 500 ─────────────────────────────────────────────

    @Test
    void handleThrowable_returns500() {
        ProblemDetail problem = handler.handleThrowable(new RuntimeException("crash"));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    @Test
    void handleThrowable_titleIsSet() {
        ProblemDetail problem = handler.handleThrowable(new RuntimeException("crash"));
        assertThat(problem.getTitle()).isEqualTo("Erreur interne critique");
    }

    @Test
    void handleThrowable_detailContainsMessage() {
        ProblemDetail problem = handler.handleThrowable(
                new IllegalStateException("État invalide"));
        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getDetail()).isEqualTo("Une erreur critique est survenue.");
    }

    @Test
    void handleThrowable_nullMessage_noException() {
        // NullPointerException avec message null ne doit pas planter le handler
        ProblemDetail problem = handler.handleThrowable(new NullPointerException());
        assertThat(problem.getStatus()).isEqualTo(500);
    }

    @Test
    void handleThrowable_oom_returns503() {
        ProblemDetail problem = handler.handleThrowable(new OutOfMemoryError("Java heap space"));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(problem.getTitle()).isEqualTo("Serveur surchargé (Mémoire)");
    }

    // ── ResponseStatusException → statut + Retry-After sur 429 ────────────────

    @Test
    void handleResponseStatus_429_carriesRetryAfterHeader() {
        ResponseEntity<ProblemDetail> response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Trop d'ingestions actives"));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).contains("Trop d'ingestions actives");
    }

    @Test
    void handleResponseStatus_nonThrottle_hasNoRetryAfter() {
        ResponseEntity<ProblemDetail> response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Tâche inconnue"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("Tâche inconnue");
    }
}
