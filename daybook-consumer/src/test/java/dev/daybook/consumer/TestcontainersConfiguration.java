package dev.daybook.consumer;

import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL and Kafka for the consumer's integration tests, as singleton containers started
 * once per test JVM (see the API module's equivalent for why they are not Spring beans).
 *
 * <p>The tests connect as the container's superuser, so Flyway creates the projection schema
 * itself. That the real consumer role is confined to that schema is tested in the API module, where
 * the role is created (DatabaseRolesIT).
 */
@TestConfiguration(proxyBeanMethods = false)
// "resource": the containers are deliberately never closed — they live for the whole test run and
// Testcontainers' cleanup container (Ryuk) removes them when the JVM exits.
@SuppressWarnings("resource")
public class TestcontainersConfiguration {

  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:16"))
          // Default 60s is too tight on a busy Docker host; a slow start is not a failure.
          .withStartupTimeout(Duration.ofMinutes(3));

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

  /** In production the API creates the event topic; here the test does, with the same shape. */
  @Bean
  NewTopic accountEntriesTopic(ConsumerProperties properties) {
    return TopicBuilder.name(properties.topic())
        .partitions(properties.partitions())
        .replicas(1)
        .build();
  }
}
