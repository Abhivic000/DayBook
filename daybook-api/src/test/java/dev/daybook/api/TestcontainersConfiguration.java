package dev.daybook.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL and Kafka in Docker for integration tests. {@code @ServiceConnection} points the
 * application at them, so tests need no connection settings.
 *
 * <p>The containers are static: one Postgres and one Kafka serve every test class in the run,
 * however many Spring contexts the tests create. {@code destroyMethod = ""} stops Spring from
 * shutting a shared container down when one context closes; Testcontainers removes them when the
 * test JVM exits. Tests stay independent by using a fresh tenant each.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  /**
   * Spring keeps every distinct test context (and its connection pool) alive for reuse, and all of
   * them share this one database. Their pools together exceed Postgres's default of 100
   * connections, so the test database allows more. Same arithmetic as production: instances × pool
   * size must stay below max_connections.
   */
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:16"))
          .withCommand("postgres", "-c", "max_connections=300");

  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

  @Bean(destroyMethod = "")
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return POSTGRES;
  }

  @Bean(destroyMethod = "")
  @ServiceConnection
  KafkaContainer kafkaContainer() {
    return KAFKA;
  }
}
