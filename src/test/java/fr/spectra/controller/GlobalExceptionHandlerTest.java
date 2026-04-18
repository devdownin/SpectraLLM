package fr.spectra.controller;

import fr.spectra.service.extraction.ExtractionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

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
    void handleGeneric_returns500() {
        ProblemDetail problem = handler.handleGeneric(new RuntimeException("crash"));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    @Test
    void handleGeneric_titleIsSet() {
        ProblemDetail problem = handler.handleGeneric(new RuntimeException("crash"));
        assertThat(problem.getTitle()).isEqualTo("Erreur interne");
    }

    @Test
    void handleGeneric_detailContainsMessage() {
        ProblemDetail problem = handler.handleGeneric(
                new IllegalStateException("État invalide"));
        assertThat(problem.getDetail()).contains("État invalide");
    }

    @Test
    void handleGeneric_nullMessage_noException() {
        // NullPointerException avec message null ne doit pas planter le handler
        ProblemDetail problem = handler.handleGeneric(new NullPointerException());
        assertThat(problem.getStatus()).isEqualTo(500);
    }
}
