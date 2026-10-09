package dev.daybook.consumer;

import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL and Kafka for the consumer's integration tests, shared across the run (see the
 * API module's equivalent for why the containers are static).
 *
 * <p>The tests connect as the container's superuser, so Flyway creates the projection schema
 * itself. That the real consumer role is confined to that schema is tested in the API module, where
 * the role is created (DatabaseRolesIT).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:16"))
          // Default 60s is too tight on a busy Docker host (seen locally); a slow start is not a
          // failure.
          .withStartupTimeout(Duration.ofMinutes(3));

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

  /** In production the API creates the event topic; here the test does, with the same shape. */
  @Bean
  NewTopic accountEntriesTopic(ConsumerProperties properties) {
    return TopicBuilder.name(properties.topic())
        .partitions(properties.partitions())
        .replicas(1)
        .build();
  }
}
