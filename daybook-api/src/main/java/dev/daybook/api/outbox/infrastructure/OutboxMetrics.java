package dev.daybook.api.outbox.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Outbox health (FR-11.3), read from the table on each scrape:
 *
 * <ul>
 *   <li>{@code daybook.outbox.unpublished} — events waiting to be published
 *   <li>{@code daybook.outbox.oldest.unpublished.age} — how long the oldest has waited. The key
 *       signal: rising means the relay or Kafka is stuck, even while the API keeps serving (ADR
 *       0014 deliberately keeps Kafka out of readiness).
 * </ul>
 *
 * <p>Both queries use the partial index on unpublished rows, so they stay cheap however large the
 * table grows. If the database is unreachable the gauges report NaN rather than failing the scrape.
 */
@Component
class OutboxMetrics {

  private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

  private final JdbcClient jdbc;

  OutboxMetrics(JdbcClient jdbc, MeterRegistry registry) {
    this.jdbc = jdbc;
    Gauge.builder("daybook.outbox.unpublished", this, OutboxMetrics::unpublished)
        .description("Outbox events not yet published to Kafka")
        .register(registry);
    Gauge.builder("daybook.outbox.oldest.unpublished.age", this, OutboxMetrics::oldestAgeSeconds)
        .description("Age of the oldest unpublished outbox event (0 when none)")
        .baseUnit("seconds")
        .register(registry);
  }

  private double unpublished() {
    return query("SELECT count(*) FROM outbox_events WHERE published_at IS NULL");
  }

  private double oldestAgeSeconds() {
    return query(
        """
        SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0)
          FROM outbox_events WHERE published_at IS NULL
        """);
  }

  private double query(String sql) {
    try {
      return jdbc.sql(sql).query(Double.class).single();
    } catch (RuntimeException e) {
      log.debug("Outbox metric query failed", e);
      return Double.NaN;
    }
  }
}
