package dev.daybook.api;

import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL and Kafka in Docker for integration tests, as <em>singleton containers</em>:
 * started once per test JVM in a static block and shared by every test class and Spring context.
 *
 * <p>They are deliberately <em>not</em> Spring beans. When containers are beans (e.g. with
 * {@code @ServiceConnection}), Spring Boot stops them whenever their test context closes — and the
 * API tests keep only one context alive ({@code spring.properties}), so every test class used to
 * start a brand-new Postgres and Kafka. Here Spring only receives their addresses as properties;
 * Testcontainers removes the containers when the JVM exits. Tests stay independent by using a fresh
 * tenant each, never by relying on a fresh database.
 */
@TestConfiguration(proxyBeanMethods = false)
// "resource": the containers are deliberately never closed — they live for the whole test run and
// Testcontainers' cleanup container (Ryuk) removes them when the JVM exits.
@SuppressWarnings("resource")
public class TestcontainersConfiguration {

  /**
   * Several Spring contexts can be open at once during a run, each with its own connection pool, so
   * the test database allows more than Postgres's default 100 connections. Same arithmetic as
   * production: instances × pool size must stay below max_connections.
   */
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:16"))
          // Default 60s is too tight on a busy Docker host; a slow start is not a failure.
          .withStartupTimeout(Duration.ofMinutes(3))
          .withCommand("postgres", "-c", "max_connections=300");

  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

  static {
    POSTGRES.start();
    KAFKA.start();
  }

  @Bean
  DynamicPropertyRegistrar containerConnections() {
    return registry -> {
      registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
      registry.add("spring.datasource.username", POSTGRES::getUsername);
      registry.add("spring.datasource.password", POSTGRES::getPassword);
      registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    };
  }
}
