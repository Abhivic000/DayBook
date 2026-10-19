package dev.daybook.consumer.deadletter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code daybook.dlq.depth} (FR-9.4, FR-11.3): messages parked in the dead-letter table, read on
 * each scrape. Any value above zero means events are missing from the projection and someone has to
 * look. If the database is unreachable the gauge reports NaN rather than failing the scrape.
 */
@Component
class DeadLetterMetrics {

  private static final Logger log = LoggerFactory.getLogger(DeadLetterMetrics.class);

  private final DeadLetterRepository deadLetters;

  DeadLetterMetrics(DeadLetterRepository deadLetters, MeterRegistry registry) {
    this.deadLetters = deadLetters;
    Gauge.builder("daybook.dlq.depth", this, DeadLetterMetrics::depth)
        .description("Messages recorded in the dead-letter table")
        .register(registry);
  }

  private double depth() {
    try {
      return deadLetters.count();
    } catch (RuntimeException e) {
      log.debug("DLQ depth query failed", e);
      return Double.NaN;
    }
  }
}
