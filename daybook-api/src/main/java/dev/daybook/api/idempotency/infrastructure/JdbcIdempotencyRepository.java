package dev.daybook.api.idempotency.infrastructure;

import dev.daybook.api.idempotency.application.IdempotencyRepository;
import dev.daybook.api.idempotency.domain.IdempotencyRecord;
import dev.daybook.api.idempotency.domain.IdempotencyStatus;
import dev.daybook.api.idempotency.domain.IdempotentResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcIdempotencyRepository implements IdempotencyRepository {

  private final JdbcClient jdbc;

  JdbcIdempotencyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean tryClaim(UUID tenantId, String key, String requestHash, Duration retention) {
    // ON CONFLICT DO NOTHING rather than catching a unique violation: in Postgres a failed
    // statement aborts the whole transaction, after which the existing row could not be read.
    // Expiry uses the database clock, so app-server clock skew cannot shorten retention.
    int inserted =
        jdbc.sql(
                """
                INSERT INTO idempotency_keys
                    (id, tenant_id, idempotency_key, request_hash, status, expires_at)
                VALUES (:id, :tenantId, :key, :requestHash, 'IN_PROGRESS',
                        now() + make_interval(secs => :retentionSeconds))
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """)
            .param("id", UUID.randomUUID())
            .param("tenantId", tenantId)
            .param("key", key)
            .param("requestHash", requestHash)
            .param("retentionSeconds", retention.toSeconds())
            .update();
    return inserted == 1;
  }

  @Override
  public Optional<IdempotencyRecord> find(UUID tenantId, String key) {
    return jdbc.sql(
            """
            SELECT tenant_id, idempotency_key, request_hash, status,
                   response_status, response_body, transaction_id
              FROM idempotency_keys
             WHERE tenant_id = :tenantId AND idempotency_key = :key
            """)
        .param("tenantId", tenantId)
        .param("key", key)
        .query(JdbcIdempotencyRepository::map)
        .optional();
  }

  @Override
  public void complete(UUID tenantId, String key, IdempotentResponse response) {
    int updated =
        jdbc.sql(
                """
                UPDATE idempotency_keys
                   SET status = 'COMPLETED', response_status = :status,
                       response_body = :body, transaction_id = :transactionId
                 WHERE tenant_id = :tenantId AND idempotency_key = :key
                   AND status = 'IN_PROGRESS'
                """)
            .param("status", response.status())
            .param("body", response.body())
            .param("transactionId", response.transactionId())
            .param("tenantId", tenantId)
            .param("key", key)
            .update();
    if (updated != 1) {
      throw new IllegalStateException("Idempotency key %s was not IN_PROGRESS".formatted(key));
    }
  }

  @Override
  public int deleteExpired() {
    return jdbc.sql("DELETE FROM idempotency_keys WHERE expires_at < now()").update();
  }

  private static IdempotencyRecord map(ResultSet rs, int rowNum) throws SQLException {
    IdempotencyStatus status = IdempotencyStatus.valueOf(rs.getString("status"));
    IdempotentResponse response =
        status == IdempotencyStatus.COMPLETED
            ? new IdempotentResponse(
                rs.getInt("response_status"),
                rs.getString("response_body"),
                rs.getObject("transaction_id", UUID.class))
            : null;
    return new IdempotencyRecord(
        rs.getObject("tenant_id", UUID.class),
        rs.getString("idempotency_key"),
        rs.getString("request_hash"),
        status,
        response);
  }
}
