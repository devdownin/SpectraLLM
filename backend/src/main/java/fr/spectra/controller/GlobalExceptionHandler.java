package fr.spectra.controller;

import fr.spectra.service.LlmUnavailableException;
import fr.spectra.service.extraction.ExtractionException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class})
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

    /** Indice de repli envoyé au client sur un 429 s'il n'a pas été précisé sur l'exception. */
    private static final String DEFAULT_RETRY_AFTER_SECONDS = "5";

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException e) {
        log.debug("ResponseStatusException: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(e.getStatusCode());
        problem.setDetail(e.getReason());
        HttpHeaders headers = new HttpHeaders();
        // 429 (contre-pression d'ingestion) : joindre un Retry-After pour que le client
        // temporise au lieu de marteler l'API.
        if (e.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            headers.add(HttpHeaders.RETRY_AFTER, DEFAULT_RETRY_AFTER_SECONDS);
        }
        return new ResponseEntity<>(problem, headers, e.getStatusCode());
    }

    @ExceptionHandler(fr.spectra.service.ChromaDbClient.EmbeddingModelMismatchException.class)
    public ProblemDetail handleEmbeddingMismatch(fr.spectra.service.ChromaDbClient.EmbeddingModelMismatchException e) {
        log.error("Incohérence modèle d'embedding ↔ index vectoriel : {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Modèle d'embedding incompatible avec l'index");
        problem.setDetail(e.getMessage());
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

    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(org.springframework.web.context.request.async.AsyncRequestNotUsableException e) {
        log.debug("Le client s'est déconnecté (Broken pipe): {}", e.getMessage());
    }

    @ExceptionHandler(Throwable.class)
    public ProblemDetail handleThrowable(Throwable e) {
        boolean isOom = e instanceof OutOfMemoryError || 
                       (e.getCause() != null && e.getCause() instanceof OutOfMemoryError) ||
                       (e.getMessage() != null && e.getMessage().contains("OutOfMemoryError"));

        if (isOom) {
            log.error("ERREUR CRITIQUE : Mémoire saturée (OOM) détectée. Tentative de récupération GC.");
            System.gc();
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
            problem.setTitle("Serveur surchargé (Mémoire)");
            problem.setDetail("Le serveur est temporairement à court de mémoire. Veuillez réessayer dans quelques instants.");
            return problem;
        }

        log.error("Erreur non gérée: {}", e.getMessage(), e);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Erreur interne critique");
        problem.setDetail("Une erreur critique est survenue.");
        return problem;
    }
}
