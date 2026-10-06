package dev.daybook.api.outbox.infrastructure;

import dev.daybook.api.outbox.application.OutboxRepository;
import dev.daybook.api.outbox.domain.OutboxMessage;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOutboxRepository implements OutboxRepository {

  /** Arbitrary but fixed: every API instance must use the same number for the relay lock. */
  static final long RELAY_LOCK_KEY = 7_420_001L;

  private final JdbcClient jdbc;

  JdbcOutboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void append(OutboxMessage message) {
    jdbc.sql(
            """
            INSERT INTO outbox_events
                (event_id, event_type, topic, message_key, payload, correlation_id)
            VALUES (:eventId, :eventType, :topic, :key, :payload, :correlationId)
            """)
        .param("eventId", message.eventId())
        .param("eventType", message.eventType())
        .param("topic", message.topic())
        .param("key", message.key())
        .param("payload", message.payload())
        .param("correlationId", message.correlationId())
        .update();
  }

  @Override
  public boolean tryLockRelay() {
    return jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
        .param("key", RELAY_LOCK_KEY)
        .query(Boolean.class)
        .single();
  }

  @Override
  public List<PendingMessage> fetchUnpublished(int limit) {
    return jdbc.sql(
            """
            SELECT id, event_id, event_type, topic, message_key, payload, correlation_id
              FROM outbox_events
             WHERE published_at IS NULL
             ORDER BY id
             LIMIT :limit
            """)
        .param("limit", limit)
        .query(
            (rs, n) ->
                new PendingMessage(
                    rs.getLong("id"),
                    new OutboxMessage(
                        rs.getObject("event_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("topic"),
                        rs.getString("message_key"),
                        rs.getString("payload"),
                        rs.getString("correlation_id"))))
        .list();
  }

  @Override
  public void markPublished(List<Long> ids) {
    if (ids.isEmpty()) {
      return;
    }
    jdbc.sql(
            """
            UPDATE outbox_events
               SET published_at = now(), attempts = attempts + 1, last_error = NULL
             WHERE id IN (:ids)
            """)
        .param("ids", ids)
        .update();
  }

  @Override
  public void markFailed(long id, String error) {
    jdbc.sql("UPDATE outbox_events SET attempts = attempts + 1, last_error = :error WHERE id = :id")
        .param("error", error.length() > 1000 ? error.substring(0, 1000) : error)
        .param("id", id)
        .update();
  }

  @Override
  public int deletePublishedOlderThan(Duration age) {
    return jdbc.sql(
            """
            DELETE FROM outbox_events
             WHERE published_at < now() - make_interval(secs => :seconds)
            """)
        .param("seconds", age.toSeconds())
        .update();
  }
}
