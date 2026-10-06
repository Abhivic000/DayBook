package dev.daybook.api.outbox.domain;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A message to be published, stored in the outbox within the business transaction that produced it.
 *
 * @param key the Kafka record key; records with the same key stay in order (one partition)
 * @param payload serialised body, published exactly as stored
 */
public record OutboxMessage(
    UUID eventId,
    String eventType,
    String topic,
    String key,
    String payload,
    @Nullable String correlationId) {

  public OutboxMessage {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(topic, "topic");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(payload, "payload");
  }
}
