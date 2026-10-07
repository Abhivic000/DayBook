package dev.daybook.api.statement.application;

/**
 * The statement projection cannot be read — e.g. the consumer service has never run, so its tables
 * do not exist yet. Reported as 503: a temporary condition of the system, not of the request.
 */
public class StatementUnavailableException extends RuntimeException {

  public StatementUnavailableException(Throwable cause) {
    super("The statement projection is not available yet", cause);
  }
}
