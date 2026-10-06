package dev.daybook.api.outbox.application;

import dev.daybook.api.outbox.domain.OutboxMessage;
import java.util.concurrent.CompletableFuture;

/** Sends a message to the broker. The future completes when the broker has acknowledged it. */
public interface MessagePublisher {

  /**
   * Starts publishing {@code message}.
   *
   * @throws RuntimeException if the message cannot even be handed to the client (e.g. the broker's
   *     metadata is unreachable)
   */
  CompletableFuture<?> publish(OutboxMessage message);
}
