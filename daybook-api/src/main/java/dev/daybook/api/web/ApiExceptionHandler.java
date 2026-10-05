package dev.daybook.api.web;

import dev.daybook.api.common.domain.DomainException;
import dev.daybook.api.common.domain.NotFoundException;
import dev.daybook.api.idempotency.domain.IdempotencyRequestInProgressException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every exception to an RFC 9457 problem (FR-12.2). Status mapping:
 *
 * <ul>
 *   <li>{@link DomainException} → 422 (business rule rejected the request)
 *   <li>{@link NotFoundException} → 404 (also for other tenants' resources — FR-1.3)
 *   <li>malformed input → 400, with per-field detail for validation failures (FR-12.3)
 *   <li>{@link IdempotencyRequestInProgressException} → 409 (FR-5.6)
 *   <li>anything else → 500, with no internal detail leaked to the client
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(DomainException.class)
  ResponseEntity<ProblemBody> rejected(DomainException e) {
    return respond(Problems.of(e));
  }

  @ExceptionHandler(NotFoundException.class)
  ResponseEntity<ProblemBody> notFound(NotFoundException e) {
    return respond(Problems.of(HttpStatus.NOT_FOUND, "not-found", e.getMessage()));
  }

  @ExceptionHandler(IdempotencyRequestInProgressException.class)
  ResponseEntity<ProblemBody> inProgress(IdempotencyRequestInProgressException e) {
    return respond(
        Problems.of(HttpStatus.CONFLICT, "idempotency-request-in-progress", e.getMessage()));
  }

  @ExceptionHandler(BadRequestException.class)
  ResponseEntity<ProblemBody> badRequest(BadRequestException e) {
    return respond(Problems.of(HttpStatus.BAD_REQUEST, e.code(), e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemBody> invalid(MethodArgumentNotValidException e) {
    List<ProblemBody.FieldProblem> errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(
                f ->
                    new ProblemBody.FieldProblem(
                        f.getField(), String.valueOf(f.getDefaultMessage())))
            .toList();
    return respond(
        Problems.of(
            HttpStatus.BAD_REQUEST, "validation-failed", "Request validation failed", errors));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemBody> unreadable(HttpMessageNotReadableException e) {
    return respond(
        Problems.of(
            HttpStatus.BAD_REQUEST,
            "malformed-request",
            "Request body is missing or not valid JSON"));
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  ResponseEntity<ProblemBody> missingHeader(MissingRequestHeaderException e) {
    return respond(
        Problems.of(
            HttpStatus.BAD_REQUEST,
            "missing-header",
            "Required header '%s' is missing".formatted(e.getHeaderName())));
  }

  @ExceptionHandler({
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  ResponseEntity<ProblemBody> badParameter(Exception e) {
    return respond(
        Problems.of(HttpStatus.BAD_REQUEST, "invalid-parameter", "A request parameter is invalid"));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ProblemBody> noRoute(NoResourceFoundException e) {
    return respond(Problems.of(HttpStatus.NOT_FOUND, "not-found", "No such endpoint"));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ProblemBody> wrongMethod(HttpRequestMethodNotSupportedException e) {
    return respond(
        Problems.of(
            HttpStatus.METHOD_NOT_ALLOWED,
            "method-not-allowed",
            "Method %s is not supported here".formatted(e.getMethod())));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemBody> unexpected(Exception e) {
    log.error("Unhandled exception", e);
    return respond(
        Problems.of(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal-error",
            "An unexpected error occurred. Quote the correlation id when reporting it."));
  }

  private static ResponseEntity<ProblemBody> respond(ProblemBody problem) {
    return ResponseEntity.status(problem.status())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }
}
