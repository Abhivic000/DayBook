package dev.daybook.api.common.domain;

/**
 * The requested resource does not exist for the calling tenant. Reported as 404 — also for
 * resources that exist under another tenant, so their existence is never leaked (FR-1.3).
 *
 * <p>Deliberately not a {@link DomainException}: a not-found is not a final business decision, so
 * it rolls back and is not stored as an idempotent response (ADR 0007).
 */
public abstract class NotFoundException extends RuntimeException {

  protected NotFoundException(String message) {
    super(message);
  }
}
