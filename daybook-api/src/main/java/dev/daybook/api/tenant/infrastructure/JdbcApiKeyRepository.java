package dev.daybook.api.tenant.infrastructure;

import dev.daybook.api.tenant.application.ApiKeyRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcApiKeyRepository implements ApiKeyRepository {

  private final JdbcClient jdbc;

  JdbcApiKeyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(UUID tenantId, String keyId, String secretHash) {
    jdbc.sql(
            """
            INSERT INTO api_keys (id, tenant_id, key_id, secret_hash, status)
            VALUES (:id, :tenantId, :keyId, :secretHash, 'ACTIVE')
            """)
        .param("id", UUID.randomUUID())
        .param("tenantId", tenantId)
        .param("keyId", keyId)
        .param("secretHash", secretHash)
        .update();
  }

  @Override
  public Optional<ActiveKey> findActive(String keyId) {
    return jdbc.sql(
            "SELECT tenant_id, secret_hash FROM api_keys WHERE key_id = :keyId AND status = 'ACTIVE'")
        .param("keyId", keyId)
        .query(
            (rs, n) ->
                new ActiveKey(rs.getObject("tenant_id", UUID.class), rs.getString("secret_hash")))
        .optional();
  }
}
