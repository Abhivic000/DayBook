package dev.daybook.api.common.domain;

/**
 * A business rule rejected the operation (e.g. insufficient funds). These are expected outcomes,
 * reported to clients as 422 with a machine-readable {@link #code()}, and stored as an idempotent
 * request's final answer (ADR 0007).
 *
 * <p>Programming errors (e.g. building an unbalanced posting) are not DomainExceptions; they throw
 * standard unchecked exceptions and surface as 500s.
 */
public abstract class DomainException extends RuntimeException {

  protected DomainException(String message) {
    super(message);
  }

  /** Stable, kebab-case identifier used as the problem type, e.g. {@code insufficient-funds}. */
  public abstract String code();
}
