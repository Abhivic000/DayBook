package dev.daybook.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import dev.daybook.api.TestcontainersConfiguration;
import dev.daybook.api.common.domain.Money;
import dev.daybook.api.outbox.application.OutboxRelay;
import dev.daybook.api.support.LedgerTestData;
import dev.daybook.api.transaction.domain.Transaction;
import dev.daybook.api.transfer.application.TransferCommand;
import dev.daybook.api.transfer.application.TransferService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * PRD §8 / SRS acceptance 3: an event committed before the process dies is still published after
 * restart (INV-7, NFR-4).
 *
 * <p>The scheduled relay is switched off, so a committed transfer's events sit in the outbox
 * exactly as they would if the API had been killed between COMMIT and publish. Calling the relay by
 * hand plays the part of the restarted process. (The real-process version, with a hard kill, is
 * {@code scripts/crash-test.ps1}.)
 */
@SpringBootTest(properties = "daybook.outbox.relay-enabled=false")
@Import(TestcontainersConfiguration.class)
class OutboxCrashRecoveryIT {

  private static final String TOPIC = "daybook.account-entries.v1";

  @Autowired TransferService transfers;
  @Autowired OutboxRelay relay;
  @Autowired LedgerTestData data;
  @Autowired JdbcClient jdbc;
  @Autowired ConsumerFactory<?, ?> consumerFactory;

  @Test
  void eventsCommittedBeforeTheCrashArePublishedAfterRestart() throws Exception {
    Transaction txn = committedTransferWhoseRelayNeverRan();

    // "Crashed": the money moved and the events are durable, but nothing reached Kafka.
    Thread.sleep(1_000); // longer than a normal relay cycle
    assertThat(unpublished(txn)).isEqualTo(2);
    assertThat(recordsInKafkaFor(txn, Duration.ofSeconds(2))).isEmpty();

    // "Restarted": a relay runs again and finds the outbox rows.
    relayUntilNothingPendingFor(txn);

    assertThat(unpublished(txn)).isZero();
    List<ConsumerRecord<String, String>> records = recordsInKafkaFor(txn, Duration.ofSeconds(5));
    assertThat(records).hasSize(2); // one per ledger entry, each exactly once
  }

  @Test
  void crashAfterBrokerAckButBeforeMarkingPublishedRepublishes() throws Exception {
    Transaction txn = committedTransferWhoseRelayNeverRan();
    relayUntilNothingPendingFor(txn);

    // The broker had the records, but the UPDATE marking them published never committed.
    jdbc.sql(
            "UPDATE outbox_events SET published_at = NULL"
                + " WHERE payload::jsonb ->> 'transactionId' = ?")
        .param(txn.id().toString())
        .update();
    relayUntilNothingPendingFor(txn);

    // At-least-once: each event is now in Kafka twice, with the same event id. Consumers must
    // deduplicate (they do: by account version — see the consumer's duplicate tests).
    List<ConsumerRecord<String, String>> records = recordsInKafkaFor(txn, Duration.ofSeconds(5));
    assertThat(records).hasSize(4);
    assertThat(
            records.stream()
                .map(
                    r ->
                        new String(
                            r.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8))
                .distinct())
        .hasSize(2);
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private Transaction committedTransferWhoseRelayNeverRan() {
    UUID tenant = data.newTenant();
    UUID from = data.newFundedUserAccount(tenant, 1_000);
    UUID to = data.newUserAccount(tenant);
    return transfers.transfer(new TransferCommand(tenant, from, to, Money.ofMinor(400)));
  }

  private long unpublished(Transaction txn) {
    return jdbc.sql(
            """
            SELECT count(*) FROM outbox_events
             WHERE payload::jsonb ->> 'transactionId' = ? AND published_at IS NULL
            """)
        .param(txn.id().toString())
        .query(Long.class)
        .single();
  }

  private void relayUntilNothingPendingFor(Transaction txn) throws InterruptedException {
    for (int attempt = 0; attempt < 100 && unpublished(txn) > 0; attempt++) {
      relay.relayOnce();
      Thread.sleep(50);
    }
    assertThat(unpublished(txn)).as("outbox rows still unpublished").isZero();
  }

  /** Reads the whole topic with a fresh group for {@code window}, keeping this txn's records. */
  private List<ConsumerRecord<String, String>> recordsInKafkaFor(Transaction txn, Duration window) {
    Properties overrides = new Properties();
    overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    overrides.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    overrides.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    @SuppressWarnings("unchecked")
    Consumer<String, String> consumer =
        (Consumer<String, String>)
            consumerFactory.createConsumer("crash-it-" + UUID.randomUUID(), null, null, overrides);
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    try (consumer) {
      consumer.subscribe(Set.of(TOPIC));
      long deadline = System.nanoTime() + window.toNanos();
      while (System.nanoTime() < deadline) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
          if (record.value().contains(txn.id().toString())) {
            found.add(record);
          }
        }
      }
    }
    return found;
  }
}
