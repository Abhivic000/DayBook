package dev.daybook.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.daybook.api.TestcontainersConfiguration;
import dev.daybook.api.account.domain.InsufficientFundsException;
import dev.daybook.api.common.domain.Money;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Ledger → outbox → relay → Kafka, against real Postgres and a real Kafka broker. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxIT {

  private static final String TOPIC = "daybook.account-entries.v1";
  private static final Duration WAIT = Duration.ofSeconds(30);

  @Autowired TransferService transfers;
  @Autowired LedgerTestData data;
  @Autowired JdbcClient jdbc;
  @Autowired JsonMapper json;
  @Autowired ConsumerFactory<?, ?> consumerFactory;

  @Test
  void eachEntryWritesOneOutboxEventCarryingVersionAndBalanceAfter() {
    UUID tenant = data.newTenant();
    UUID from = data.newFundedUserAccount(tenant, 1_000); // funding credit -> version 1
    UUID to = data.newUserAccount(tenant);

    Transaction txn = transfer(tenant, from, to, 300);

    List<JsonNode> events = outboxEventsFor(txn.id());
    assertThat(events).hasSize(2);
    JsonNode debit = eventFor(events, from);
    assertThat(debit.get("direction").asString()).isEqualTo("DEBIT");
    assertThat(debit.get("accountVersion").asLong()).isEqualTo(2);
    assertThat(debit.get("balanceAfterMinor").asLong()).isEqualTo(700);
    JsonNode credit = eventFor(events, to);
    assertThat(credit.get("direction").asString()).isEqualTo("CREDIT");
    assertThat(credit.get("accountVersion").asLong()).isEqualTo(1);
    assertThat(credit.get("balanceAfterMinor").asLong()).isEqualTo(300);
    assertThat(credit.get("tenantId").asString()).isEqualTo(tenant.toString());
  }

  @Test
  void rejectedTransferWritesNoEvents() {
    UUID tenant = data.newTenant();
    UUID from = data.newFundedUserAccount(tenant, 100);
    UUID to = data.newUserAccount(tenant);
    long before = outboxCountFor(tenant);

    assertThatThrownBy(() -> transfer(tenant, from, to, 101))
        .isInstanceOf(InsufficientFundsException.class);

    assertThat(outboxCountFor(tenant)).isEqualTo(before);
  }

  @Test
  void relayPublishesEventsKeyedByAccountAndMarksThemPublished() throws Exception {
    UUID tenant = data.newTenant();
    UUID from = data.newFundedUserAccount(tenant, 1_000);
    UUID to = data.newUserAccount(tenant);

    Transaction txn = transfer(tenant, from, to, 250);

    List<ConsumerRecord<String, String>> records =
        consume(r -> r.value().contains(txn.id().toString()), found -> found.size() == 2);
    assertThat(records)
        .extracting(ConsumerRecord::key)
        .containsExactlyInAnyOrder(from.toString(), to.toString());
    for (ConsumerRecord<String, String> record : records) {
      String headerEventId =
          new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8);
      assertThat(json.readTree(record.value()).get("eventId").asString()).isEqualTo(headerEventId);
    }
    awaitPublished(txn.id());
  }

  @Test
  void oneAccountsEventsArriveInVersionOrderEvenUnderConcurrentTransfers() throws Exception {
    UUID tenant = data.newTenant();
    UUID hot = data.newFundedUserAccount(tenant, 100_000); // version 1
    List<UUID> destinations = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      destinations.add(data.newUserAccount(tenant));
    }

    int transferCount = 20;
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < transferCount; i++) {
        UUID to = destinations.get(i % destinations.size());
        futures.add(pool.submit(() -> transfer(tenant, hot, to, 10)));
      }
      for (Future<?> f : futures) {
        f.get(60, TimeUnit.SECONDS);
      }
    }

    String hotKey = hot.toString();
    List<ConsumerRecord<String, String>> records =
        consume(r -> hotKey.equals(r.key()), found -> found.size() == transferCount + 1);
    List<Long> versions = new ArrayList<>();
    for (ConsumerRecord<String, String> record : records) {
      versions.add(json.readTree(record.value()).get("accountVersion").asLong());
    }
    List<Long> expected = new ArrayList<>();
    for (long v = 1; v <= transferCount + 1; v++) {
      expected.add(v);
    }
    assertThat(versions).as("versions in partition order").isEqualTo(expected);
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private Transaction transfer(UUID tenant, UUID from, UUID to, long amount) {
    return transfers.transfer(new TransferCommand(tenant, from, to, Money.ofMinor(amount)));
  }

  private List<JsonNode> outboxEventsFor(UUID transactionId) {
    return jdbc
        .sql("SELECT payload FROM outbox_events WHERE payload::jsonb ->> 'transactionId' = ?")
        .param(transactionId.toString())
        .query(String.class)
        .list()
        .stream()
        .map(json::readTree)
        .toList();
  }

  private static JsonNode eventFor(List<JsonNode> events, UUID accountId) {
    return events.stream()
        .filter(e -> e.get("accountId").asString().equals(accountId.toString()))
        .findFirst()
        .orElseThrow();
  }

  private long outboxCountFor(UUID tenant) {
    return jdbc.sql("SELECT count(*) FROM outbox_events WHERE payload::jsonb ->> 'tenantId' = ?")
        .param(tenant.toString())
        .query(Long.class)
        .single();
  }

  private void awaitPublished(UUID transactionId) throws InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (System.nanoTime() < deadline) {
      long unpublished =
          jdbc.sql(
                  """
                  SELECT count(*) FROM outbox_events
                   WHERE payload::jsonb ->> 'transactionId' = ? AND published_at IS NULL
                  """)
              .param(transactionId.toString())
              .query(Long.class)
              .single();
      if (unpublished == 0) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Outbox rows were not marked published within " + WAIT);
  }

  /**
   * Reads the topic from the beginning with a fresh consumer group, collecting records that match
   * {@code filter}, until {@code done} is satisfied or the wait expires. The topic is shared by all
   * tests, so the filter picks out this test's records.
   */
  private List<ConsumerRecord<String, String>> consume(
      Predicate<ConsumerRecord<String, String>> filter,
      Predicate<List<ConsumerRecord<String, String>>> done) {
    Properties overrides = new Properties();
    overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    overrides.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    overrides.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    @SuppressWarnings("unchecked")
    Consumer<String, String> consumer =
        (Consumer<String, String>)
            consumerFactory.createConsumer("outbox-it-" + UUID.randomUUID(), null, null, overrides);
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    try (consumer) {
      consumer.subscribe(Set.of(TOPIC));
      long deadline = System.nanoTime() + WAIT.toNanos();
      while (System.nanoTime() < deadline) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
          if (filter.test(record)) {
            found.add(record);
          }
        }
        if (done.test(found)) {
          return found;
        }
      }
    }
    throw new AssertionError(
        "Expected records not consumed within " + WAIT + "; found " + found.size());
  }
}
