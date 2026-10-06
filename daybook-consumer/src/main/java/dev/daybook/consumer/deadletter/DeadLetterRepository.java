package dev.daybook.consumer.deadletter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DeadLetterRepository {

  private final JdbcClient jdbc;

  DeadLetterRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Stores a dead letter; a record already stored (same original position) is ignored. */
  public void record(DeadLetter letter) {
    jdbc.sql(
            """
            INSERT INTO projection.dead_letters
                (original_topic, original_partition, original_offset, message_key, payload,
                 exception_class, exception_message, correlation_id, failed_at)
            VALUES (:topic, :partition, :offset, :key, :payload,
                    :exceptionClass, :exceptionMessage, :correlationId, :failedAt)
            ON CONFLICT (original_topic, original_partition, original_offset) DO NOTHING
            """)
        .param("topic", letter.originalTopic())
        .param("partition", letter.originalPartition())
        .param("offset", letter.originalOffset())
        .param("key", letter.messageKey())
        .param("payload", letter.payload())
        .param("exceptionClass", letter.exceptionClass())
        .param("exceptionMessage", letter.exceptionMessage())
        .param("correlationId", letter.correlationId())
        .param("failedAt", OffsetDateTime.ofInstant(letter.failedAt(), ZoneOffset.UTC))
        .update();
  }

  /** DLQ depth (FR-9.4). */
  public long count() {
    return jdbc.sql("SELECT count(*) FROM projection.dead_letters").query(Long.class).single();
  }

  public List<DeadLetter> newest(int limit) {
    return jdbc.sql(
            """
            SELECT original_topic, original_partition, original_offset, message_key, payload,
                   exception_class, exception_message, correlation_id, failed_at
              FROM projection.dead_letters
             ORDER BY recorded_at DESC, id DESC
             LIMIT :limit
            """)
        .param("limit", limit)
        .query(
            (rs, n) ->
                new DeadLetter(
                    rs.getString("original_topic"),
                    rs.getInt("original_partition"),
                    rs.getLong("original_offset"),
                    rs.getString("message_key"),
                    rs.getString("payload"),
                    rs.getString("exception_class"),
                    rs.getString("exception_message"),
                    rs.getString("correlation_id"),
                    rs.getObject("failed_at", OffsetDateTime.class).toInstant()))
        .list();
  }
}
