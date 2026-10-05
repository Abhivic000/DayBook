package dev.daybook.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An RFC 9457 problem document (FR-12.2). One shape for every error this API produces, so it can be
 * serialised identically whether returned directly or stored as an idempotent response.
 *
 * @param type machine-readable error type, e.g. {@code
 *     https://daybook.dev/errors/insufficient-funds}
 * @param errors per-field validation failures (FR-12.3), present only for validation errors
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemBody(
    String type,
    String title,
    int status,
    String detail,
    @Nullable String correlationId,
    @Nullable List<FieldProblem> errors) {

  public record FieldProblem(String field, String message) {}
}
