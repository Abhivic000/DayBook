package dev.daybook.consumer.deadletter;

import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * A listener factory for the dead-letter recorder: same settings as the default, but on failure it
 * retries every 5 seconds forever. Recording a dead letter fails only if the database is
 * unavailable; waiting for it to return loses nothing, whereas giving up would lose the failure
 * record itself.
 */
@Configuration(proxyBeanMethods = false)
class DeadLetterListenerConfig {

  static final String FACTORY = "deadLetterListenerFactory";

  @Bean(FACTORY)
  ConcurrentKafkaListenerContainerFactory<Object, Object> deadLetterListenerFactory(
      ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
      ConsumerFactory<Object, Object> consumerFactory) {
    ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    configurer.configure(factory, consumerFactory);
    factory.setConcurrency(1);
    factory.setCommonErrorHandler(
        new DefaultErrorHandler(new FixedBackOff(5_000, FixedBackOff.UNLIMITED_ATTEMPTS)));
    return factory;
  }
}
