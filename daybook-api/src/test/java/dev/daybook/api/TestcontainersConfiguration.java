package dev.daybook.api;

import java.time.Duration;
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
 * <p>The containers are static, so contexts that are alive at the same time share them. Note that
 * Spring Boot stops a context's containers when that context closes (despite {@code destroyMethod =
 * ""}), and the API tests keep only one context alive ({@code spring.properties}), so a new context
 * generally starts on a fresh database. Tests therefore never rely on state from another class, and
 * stay independent within a class by using a fresh tenant each.
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
          // Default 60s is too tight on a busy Docker host (seen locally); a slow start is not a
          // failure.
          .withStartupTimeout(Duration.ofMinutes(3))
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
