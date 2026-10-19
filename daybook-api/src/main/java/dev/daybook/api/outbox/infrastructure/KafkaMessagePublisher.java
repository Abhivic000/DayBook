package dev.daybook.api.outbox.infrastructure;

import dev.daybook.api.common.tracing.TraceParents;
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
 *
 * <p>Each send continues the trace of the request that wrote the message (FR-11.4): the template's
 * own observation then adds a W3C {@code traceparent} header, and the consumer carries on from it.
 */
@Component
class KafkaMessagePublisher implements MessagePublisher {

  static final String EVENT_ID_HEADER = "eventId";
  static final String EVENT_TYPE_HEADER = "eventType";
  static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  private final KafkaTemplate<String, String> kafka;
  private final TraceParents traces;

  KafkaMessagePublisher(KafkaTemplate<String, String> kafka, TraceParents traces) {
    this.kafka = kafka;
    this.traces = traces;
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
    return traces.continueTrace(message.traceparent(), "outbox publish", () -> kafka.send(record));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
