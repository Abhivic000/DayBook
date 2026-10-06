package dev.daybook.consumer.deadletter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.jspecify.annotations.Nullable;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

/**
 * Copies every dead-lettered record into {@code dead_letters} (ADR 0012).
 *
 * <p>Uses its own listener factory ({@link DeadLetterListenerConfig}): if recording fails it
 * retries indefinitely instead of dead-lettering — the main error handler would publish the failure
 * back onto the very topic this listener reads.
 *
 * <p>Spring Kafka's dead-letter publisher attaches the failure details as headers; partition,
 * offset and timestamp are binary (4- and 8-byte big-endian numbers), the rest UTF-8 text.
 */
@Component
class DeadLetterRecorder {

  static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  private final DeadLetterRepository deadLetters;

  DeadLetterRecorder(DeadLetterRepository deadLetters) {
    this.deadLetters = deadLetters;
  }

  @KafkaListener(
      id = "dead-letter-recorder",
      topics = "${daybook.consumer.topic:daybook.account-entries.v1}.dlq",
      groupId = "daybook-dead-letter-recorder",
      containerFactory = DeadLetterListenerConfig.FACTORY)
  void onDeadLetter(ConsumerRecord<String, String> record) {
    deadLetters.record(toDeadLetter(record));
  }

  static DeadLetter toDeadLetter(ConsumerRecord<String, String> record) {
    String originalTopic = text(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
    byte[] partition = bytes(record, KafkaHeaders.DLT_ORIGINAL_PARTITION);
    byte[] offset = bytes(record, KafkaHeaders.DLT_ORIGINAL_OFFSET);
    byte[] timestamp = bytes(record, KafkaHeaders.DLT_ORIGINAL_TIMESTAMP);
    String causeClass = text(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
    return new DeadLetter(
        // Without the original-position headers, fall back to this record's own position:
        // still unique, so the dead letter is never silently skipped.
        originalTopic != null ? originalTopic : record.topic(),
        partition != null ? ByteBuffer.wrap(partition).getInt() : record.partition(),
        offset != null ? ByteBuffer.wrap(offset).getLong() : record.offset(),
        record.key(),
        record.value(),
        causeClass != null ? causeClass : text(record, KafkaHeaders.DLT_EXCEPTION_FQCN),
        text(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
        text(record, CORRELATION_ID_HEADER),
        Instant.ofEpochMilli(
            timestamp != null ? ByteBuffer.wrap(timestamp).getLong() : record.timestamp()));
  }

  private static byte @Nullable [] bytes(ConsumerRecord<?, ?> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : header.value();
  }

  private static @Nullable String text(ConsumerRecord<?, ?> record, String name) {
    byte[] value = bytes(record, name);
    return value == null ? null : new String(value, StandardCharsets.UTF_8);
  }
}
