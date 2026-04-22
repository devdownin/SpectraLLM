package fr.spectra.controller;

import fr.spectra.service.LlmUnavailableException;
import fr.spectra.service.extraction.ExtractionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException e) {
        log.debug("Ressource non trouvée: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Ressource non trouvée");
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail handleBadRequest(Exception e) {
        log.debug("Requête invalide: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Paramètre invalide");
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        log.debug("Argument invalide: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Paramètre invalide");
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException e) {
        log.debug("ResponseStatusException: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(e.getStatusCode());
        problem.setDetail(e.getReason());
        return problem;
    }

    @ExceptionHandler(LlmUnavailableException.class)
    public ProblemDetail handleLlmUnavailable(LlmUnavailableException e) {
        log.warn("LLM indisponible (circuit ouvert): {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setTitle("Modèle LLM indisponible");
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        log.debug("Validation échouée: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Paramètre invalide");
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " : " + fe.getDefaultMessage())
                .collect(java.util.stream.Collectors.joining(", "));
        problem.setDetail(detail.isBlank() ? e.getMessage() : detail);
        return problem;
    }

    @ExceptionHandler(ExtractionException.class)
    public ProblemDetail handleExtraction(ExtractionException e) {
        log.error("Erreur d'extraction: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setTitle("Erreur d'extraction");
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception e) {
        log.error("Erreur interne: {}", e.getMessage(), e);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Erreur interne");
        problem.setDetail(e.getMessage());
        return problem;
    }
}
