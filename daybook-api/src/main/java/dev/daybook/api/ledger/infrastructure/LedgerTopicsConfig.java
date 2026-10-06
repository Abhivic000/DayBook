package dev.daybook.api.ledger.infrastructure;

import dev.daybook.api.ledger.application.LedgerEventProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the event topic. Spring's KafkaAdmin creates it on startup if missing. If Kafka is down
 * at startup the API still starts: events wait in the outbox (failure matrix: "Kafka down").
 */
@Configuration(proxyBeanMethods = false)
class LedgerTopicsConfig {

  @Bean
  NewTopic accountEntriesTopic(LedgerEventProperties properties) {
    return TopicBuilder.name(properties.accountEntriesTopic())
        .partitions(properties.accountEntriesPartitions())
        .replicas(1) // single broker locally; production would use 3
        .build();
  }
}
