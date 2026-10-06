package dev.daybook.consumer.statement.domain;

/** A record that is not a valid event for this consumer. Dead-lettered without retries. */
public class MalformedEventException extends RuntimeException {

  public MalformedEventException(String message) {
    super(message);
  }
}
