package dev.daybook.consumer.messaging;

import dev.daybook.consumer.ConsumerProperties;
import dev.daybook.consumer.statement.domain.MalformedEventException;
import dev.daybook.consumer.statement.domain.ProjectionMismatchException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

/**
 * What happens when an event cannot be processed (FR-9.3).
 *
 * <p>Spring Kafka's default would retry 10 times and then <em>log and skip</em> the record —
 * silently losing it. Here a failing record is retried with exponential backoff and then published
 * to the dead-letter topic with the failure attached, so nothing is ever dropped and one bad record
 * cannot block the rest of its partition forever.
 */
@Configuration(proxyBeanMethods = false)
class KafkaErrorHandlingConfig {

  @Bean
  DefaultErrorHandler kafkaErrorHandler(
      KafkaOperations<Object, Object> kafka, ConsumerProperties properties) {
    // Same partition number on the DLQ topic: an account's dead letters stay in order too.
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafka,
            (record, exception) ->
                new TopicPartition(properties.deadLetterTopic(), record.partition()));

    ConsumerProperties.Retry retry = properties.retry();
    ExponentialBackOff backOff = new ExponentialBackOff();
    backOff.setInitialInterval(retry.initialInterval().toMillis());
    backOff.setMultiplier(2.0);
    backOff.setMaxInterval(retry.maxInterval().toMillis());
    backOff.setMaxAttempts(retry.maxAttempts() - 1); // retries after the first attempt

    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
    // Retrying cannot fix these: dead-letter them on the first failure.
    handler.addNotRetryableExceptions(
        JacksonException.class,
        DeserializationException.class,
        MalformedEventException.class,
        ProjectionMismatchException.class);
    return handler;
  }

  @Bean
  NewTopic deadLetterTopic(ConsumerProperties properties) {
    return TopicBuilder.name(properties.deadLetterTopic())
        .partitions(properties.partitions())
        .replicas(1)
        .build();
  }
}
