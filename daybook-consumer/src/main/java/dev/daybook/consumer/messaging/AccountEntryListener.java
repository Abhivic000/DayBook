package dev.daybook.consumer.messaging;

import dev.daybook.consumer.statement.application.StatementProjector;
import dev.daybook.consumer.statement.domain.AccountEntryEvent;
import dev.daybook.consumer.statement.domain.MalformedEventException;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads ledger events and hands each to the projector. Exceptions propagate to the container's
 * error handler, which retries and then dead-letters (see {@link KafkaErrorHandlingConfig}).
 */
@Component
class AccountEntryListener {

  static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
  private static final String MDC_KEY = "correlationId";
  private static final Logger log = LoggerFactory.getLogger(AccountEntryListener.class);

  private final StatementProjector projector;
  private final JsonMapper json;

  AccountEntryListener(StatementProjector projector, JsonMapper json) {
    this.projector = projector;
    this.json = json;
  }

  @KafkaListener(
      id = "statement-projector",
      topics = "${daybook.consumer.topic:daybook.account-entries.v1}",
      groupId = "${daybook.consumer.group-id:daybook-statement-projector}")
  void onEvent(ConsumerRecord<String, String> record) {
    // FR-11.2: carry the request's correlation id into this service's logs.
    String correlationId = header(record, CORRELATION_ID_HEADER);
    if (correlationId != null) {
      MDC.put(MDC_KEY, correlationId);
    }
    try {
      if (record.value() == null) {
        throw new MalformedEventException("Record has no value");
      }
      AccountEntryEvent event = json.readValue(record.value(), AccountEntryEvent.class);
      if (!event.accountId().toString().equals(record.key())) {
        // The key decides the partition, and so the ordering guarantee (ADR 0010).
        throw new MalformedEventException(
            "Record key %s does not match event account %s"
                .formatted(record.key(), event.accountId()));
      }
      StatementProjector.Outcome outcome = projector.project(event);
      log.debug(
          "Event {} for account {} v{}: {}",
          event.eventId(),
          event.accountId(),
          event.accountVersion(),
          outcome);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  private static @Nullable String header(ConsumerRecord<?, ?> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}
