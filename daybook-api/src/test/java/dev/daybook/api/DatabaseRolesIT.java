package dev.daybook.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ADR 0005/0013: the consumer's database role can work only in its own schema. Connects as that
 * role, exactly as the consumer service does.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DatabaseRolesIT {

  private static final String CONSUMER_ROLE = "daybook_consumer";
  private static final String CONSUMER_PASSWORD = "test-consumer-password"; // test properties

  @Autowired JdbcClient apiJdbc; // ensures migrations have run before connecting as the consumer

  @Test
  void consumerRoleCannotReadTheLedger() {
    assertThatThrownBy(() -> asConsumer("SELECT count(*) FROM accounts"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
    assertThatThrownBy(() -> asConsumer("SELECT count(*) FROM ledger_entries"))
        .hasMessageContaining("permission denied");
    assertThatThrownBy(() -> asConsumer("SELECT count(*) FROM api_keys"))
        .hasMessageContaining("permission denied");
  }

  @Test
  void consumerRoleCannotWriteTheLedger() {
    assertThatThrownBy(
            () ->
                asConsumer(
                    "INSERT INTO tenants (id, name) VALUES ('%s', 'x')"
                        .formatted(UUID.randomUUID())))
        .hasMessageContaining("permission denied");
  }

  @Test
  void consumerRoleOwnsTheProjectionSchemaAndTheApiCanReadIt() throws SQLException {
    String table = "projection.roles_it_" + UUID.randomUUID().toString().replace("-", "");
    asConsumer("CREATE TABLE " + table + " (id int)");
    asConsumer("INSERT INTO " + table + " VALUES (42)");
    try {
      // Note: in the test container the API's user is a superuser, which bypasses privilege
      // checks, so this proves the schema is usable rather than the exact SELECT-only grant.
      assertThat(apiJdbc.sql("SELECT id FROM " + table).query(Integer.class).single())
          .isEqualTo(42);
    } finally {
      asConsumer("DROP TABLE " + table);
    }
  }

  private static void asConsumer(String sql) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                TestcontainersConfiguration.POSTGRES.getJdbcUrl(),
                CONSUMER_ROLE,
                CONSUMER_PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
