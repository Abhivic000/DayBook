package dev.daybook.api.account.infrastructure;

import dev.daybook.api.account.application.AccountRepository;
import dev.daybook.api.account.domain.Account;
import dev.daybook.api.account.domain.AccountStatus;
import dev.daybook.api.account.domain.AccountType;
import dev.daybook.api.common.domain.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAccountRepository implements AccountRepository {

  private static final String COLUMNS =
      "id, tenant_id, type, balance_minor, version, allow_negative, status";

  private final JdbcClient jdbc;

  JdbcAccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(Account account) {
    jdbc.sql(
            """
            INSERT INTO accounts (id, tenant_id, type, balance_minor, version, allow_negative, status)
            VALUES (:id, :tenantId, :type, :balance, :version, :allowNegative, :status)
            """)
        .param("id", account.id())
        .param("tenantId", account.tenantId())
        .param("type", account.type().name())
        .param("balance", account.balance().minor())
        .param("version", account.version())
        .param("allowNegative", account.allowNegative())
        .param("status", account.status().name())
        .update();
  }

  @Override
  public Optional<Account> findById(UUID tenantId, UUID accountId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE tenant_id = :tenantId AND id = :id")
        .param("tenantId", tenantId)
        .param("id", accountId)
        .query(JdbcAccountRepository::map)
        .optional();
  }

  @Override
  public Optional<Account> findSystemAccount(UUID tenantId, AccountType type) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM accounts WHERE tenant_id = :tenantId AND type = :type")
        .param("tenantId", tenantId)
        .param("type", type.name())
        .query(JdbcAccountRepository::map)
        .optional();
  }

  @Override
  public List<Account> lockForUpdate(UUID tenantId, Collection<UUID> accountIds) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + """
                 FROM accounts
                WHERE tenant_id = :tenantId AND id IN (:ids)
                ORDER BY id
                  FOR NO KEY UPDATE
                """)
        .param("tenantId", tenantId)
        .param("ids", accountIds)
        .query(JdbcAccountRepository::map)
        .list();
  }

  @Override
  public void updateBalance(Account account, long expectedVersion) {
    int updated =
        jdbc.sql(
                """
                UPDATE accounts
                   SET balance_minor = :balance, version = :version
                 WHERE id = :id AND version = :expectedVersion
                """)
            .param("balance", account.balance().minor())
            .param("version", account.version())
            .param("id", account.id())
            .param("expectedVersion", expectedVersion)
            .update();
    if (updated != 1) {
      throw new IllegalStateException(
          "Account %s changed since it was locked (expected version %d); was the row lock held?"
              .formatted(account.id(), expectedVersion));
    }
  }

  private static Account map(ResultSet rs, int rowNum) throws SQLException {
    return new Account(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        AccountType.valueOf(rs.getString("type")),
        Money.ofMinor(rs.getLong("balance_minor")),
        rs.getLong("version"),
        rs.getBoolean("allow_negative"),
        AccountStatus.valueOf(rs.getString("status")));
  }
}
