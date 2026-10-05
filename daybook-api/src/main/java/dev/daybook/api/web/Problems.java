package dev.daybook.api.web;

import dev.daybook.api.common.domain.DomainException;
import dev.daybook.api.common.web.CorrelationIdFilter;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

/** Builds {@link ProblemBody} instances with consistent types, titles and correlation ids. */
public final class Problems {

  public static final String TYPE_BASE = "https://daybook.dev/errors/";

  private Problems() {}

  public static ProblemBody of(HttpStatus status, String code, String detail) {
    return of(status, code, detail, null);
  }

  public static ProblemBody of(
      HttpStatus status,
      String code,
      String detail,
      @Nullable List<ProblemBody.FieldProblem> errors) {
    return new ProblemBody(
        TYPE_BASE + code,
        title(code),
        status.value(),
        detail,
        MDC.get(CorrelationIdFilter.MDC_KEY),
        errors);
  }

  /** A business-rule rejection: always 422, typed by the exception's stable code. */
  public static ProblemBody of(DomainException rejection) {
    return of(HttpStatus.UNPROCESSABLE_CONTENT, rejection.code(), rejection.getMessage());
  }

  /** {@code insufficient-funds} becomes {@code Insufficient funds}. */
  private static String title(String code) {
    String words = code.replace('-', ' ');
    return Character.toUpperCase(words.charAt(0)) + words.substring(1);
  }
}
