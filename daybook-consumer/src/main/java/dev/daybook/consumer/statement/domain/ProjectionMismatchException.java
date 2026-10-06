package dev.daybook.consumer.statement.domain;

import java.util.UUID;

/**
 * An event's balance does not follow from the projection's history. Retrying cannot fix that, so it
 * is dead-lettered immediately and needs a human.
 */
public class ProjectionMismatchException extends RuntimeException {

  public ProjectionMismatchException(UUID accountId, long version, long expected, long actual) {
    super(
        "Account %s version %d: expected balance after %d but event says %d"
            .formatted(accountId, version, expected, actual));
  }
}
