package dev.daybook.api.outbox.infrastructure;

import dev.daybook.api.outbox.application.MessagePublisher;
import dev.daybook.api.outbox.domain.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes outbox messages to Kafka. The event id, type and correlation id travel as record
 * headers, so consumers can deduplicate and trace without parsing the payload first.
 */
@Component
class KafkaMessagePublisher implements MessagePublisher {

  static final String EVENT_ID_HEADER = "eventId";
  static final String EVENT_TYPE_HEADER = "eventType";
  static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  private final KafkaTemplate<String, String> kafka;

  KafkaMessagePublisher(KafkaTemplate<String, String> kafka) {
    this.kafka = kafka;
  }

  @Override
  public CompletableFuture<?> publish(OutboxMessage message) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(message.topic(), message.key(), message.payload());
    record.headers().add(EVENT_ID_HEADER, bytes(message.eventId().toString()));
    record.headers().add(EVENT_TYPE_HEADER, bytes(message.eventType()));
    if (message.correlationId() != null) {
      record.headers().add(CORRELATION_ID_HEADER, bytes(message.correlationId()));
    }
    return kafka.send(record);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
