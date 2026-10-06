package dev.daybook.consumer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Consumer settings, bound from {@code daybook.consumer.*}.
 *
 * @param topic the ledger event topic (created by the API)
 * @param groupId Kafka consumer group: all instances sharing it split the partitions between them
 * @param partitions partition count of the dead-letter topic; matches the main topic so a record
 *     keeps its partition number when dead-lettered
 * @param retry how a failing record is retried before it is dead-lettered
 */
@ConfigurationProperties("daybook.consumer")
public record ConsumerProperties(
    @DefaultValue("daybook.account-entries.v1") String topic,
    @DefaultValue("daybook-statement-projector") String groupId,
    @DefaultValue("6") int partitions,
    @DefaultValue Retry retry) {

  /**
   * Retry policy: exponential backoff between attempts.
   *
   * @param maxAttempts total attempts, including the first
   */
  public record Retry(
      @DefaultValue("3") int maxAttempts,
      @DefaultValue("PT1S") Duration initialInterval,
      @DefaultValue("PT10S") Duration maxInterval) {}

  /** Records that keep failing land here (FR-9.3). */
  public String deadLetterTopic() {
    return topic + ".dlq";
  }
}
