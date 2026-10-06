package dev.daybook.consumer.statement.domain;

import java.util.UUID;

/** An event arrived ahead of one that is missing. Retried, then dead-lettered. */
public class EventGapException extends RuntimeException {

  public EventGapException(UUID accountId, long expectedVersion, long actualVersion) {
    super(
        "Account %s: expected version %d but received %d; an earlier event is missing"
            .formatted(accountId, expectedVersion, actualVersion));
  }
}
