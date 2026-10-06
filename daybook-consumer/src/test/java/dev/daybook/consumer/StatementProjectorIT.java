package dev.daybook.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Events published to a real Kafka, projected into a real Postgres. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StatementProjectorIT {

  private static final Duration WAIT = Duration.ofSeconds(30);

  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired ConsumerFactory<?, ?> consumerFactory;
  @Autowired ConsumerProperties properties;
  @Autowired JdbcClient jdbc;
  @Autowired JsonMapper json;

  @Test
  void consecutiveEventsBuildARunningBalanceStatement() throws Exception {
    Account account = new Account();
    publish(account.credit(1_000));
    publish(account.debit(300));
    publish(account.credit(50));

    awaitVersion(account.id, 3);
    assertThat(balance(account.id)).isEqualTo(750);
    assertThat(
            jdbc.sql(
                    """
                    SELECT balance_after_minor FROM projection.statement_lines
                     WHERE account_id = ? ORDER BY account_version
                    """)
                .param(account.id)
                .query(Long.class)
                .list())
        .containsExactly(1_000L, 700L, 750L);
  }

  @Test
  void duplicateDeliveriesAreAppliedExactlyOnce() throws Exception {
    Account account = new Account();
    String first = account.credit(1_000);
    publish(first);
    publish(first); // at-least-once redelivery
    publish(first);
    publish(account.credit(1));

    awaitVersion(account.id, 2);
    assertThat(lineCount(account.id)).isEqualTo(2);
    assertThat(balance(account.id)).isEqualTo(1_001);
  }

  @Test
  void eventAfterAGapIsNotAppliedAndIsDeadLettered() throws Exception {
    Account account = new Account();
    publish(account.credit(1_000)); // v1
    account.credit(5); // v2 — "lost", never published
    publish(account.credit(7)); // v3

    ConsumerRecord<String, String> deadLetter = awaitDeadLetter(account.id.toString());
    assertThat(header(deadLetter, "kafka_dlt-exception-cause-fqcn")).endsWith("EventGapException");
    assertThat(lastVersion(account.id)).isEqualTo(1); // never applied out of order
  }

  @Test
  void balanceMismatchIsDeadLetteredWithoutBeingApplied() throws Exception {
    Account account = new Account();
    publish(account.credit(1_000));
    publish(account.eventWithWrongBalance(2, "DEBIT", 100, 950)); // should be 900

    ConsumerRecord<String, String> deadLetter = awaitDeadLetter(account.id.toString());
    assertThat(header(deadLetter, "kafka_dlt-exception-cause-fqcn"))
        .endsWith("ProjectionMismatchException");
    assertThat(balance(account.id)).isEqualTo(1_000);
  }

  @Test
  void malformedRecordsAreDeadLetteredAndOtherAccountsKeepFlowing() throws Exception {
    String poisonKey = UUID.randomUUID().toString();
    kafka.send(new ProducerRecord<>(properties.topic(), poisonKey, "{not json")).get();
    Account healthy = new Account();
    publish(healthy.credit(42));

    awaitDeadLetter(poisonKey);
    awaitVersion(healthy.id, 1);
    assertThat(balance(healthy.id)).isEqualTo(42);
  }

  // --- Event builder ----------------------------------------------------------------------------

  /** Tracks one account's version and balance, producing the events the API would publish. */
  private final class Account {
    final UUID id = UUID.randomUUID();
    final UUID tenantId = UUID.randomUUID();
    long version;
    long balance;

    String credit(long amount) {
      balance += amount;
      return eventWithWrongBalance(++version, "CREDIT", amount, balance);
    }

    String debit(long amount) {
      balance -= amount;
      return eventWithWrongBalance(++version, "DEBIT", amount, balance);
    }

    /** Any event, including inconsistent ones; the name is a warning to callers. */
    String eventWithWrongBalance(long v, String direction, long amount, long balanceAfter) {
      Map<String, Object> event = new LinkedHashMap<>();
      event.put("eventId", UUID.randomUUID().toString());
      event.put("eventType", "AccountEntryPosted");
      event.put("occurredAt", Instant.now().toString());
      event.put("tenantId", tenantId.toString());
      event.put("accountId", id.toString());
      event.put("accountType", "USER");
      event.put("accountVersion", v);
      event.put("transactionId", UUID.randomUUID().toString());
      event.put("transactionType", "TRANSFER");
      event.put("direction", direction);
      event.put("amountMinor", amount);
      event.put("balanceAfterMinor", balanceAfter);
      event.put("someFutureField", "ignored by a tolerant reader");
      return json.writeValueAsString(event);
    }
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private void publish(String eventJson) throws Exception {
    String key = json.readTree(eventJson).get("accountId").asString();
    kafka.send(new ProducerRecord<>(properties.topic(), key, eventJson)).get();
  }

  private void awaitVersion(UUID accountId, long version) throws InterruptedException {
    await(
        () -> lastVersion(accountId) >= version, "account " + accountId + " to reach v" + version);
  }

  private long lastVersion(UUID accountId) {
    return jdbc.sql("SELECT last_version FROM projection.account_balances WHERE account_id = ?")
        .param(accountId)
        .query(Long.class)
        .optional()
        .orElse(0L);
  }

  private long balance(UUID accountId) {
    return jdbc.sql("SELECT balance_minor FROM projection.account_balances WHERE account_id = ?")
        .param(accountId)
        .query(Long.class)
        .single();
  }

  private long lineCount(UUID accountId) {
    return jdbc.sql("SELECT count(*) FROM projection.statement_lines WHERE account_id = ?")
        .param(accountId)
        .query(Long.class)
        .single();
  }

  private static void await(BooleanSupplier condition, String what) throws InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Timed out waiting for " + what);
  }

  private ConsumerRecord<String, String> awaitDeadLetter(String key) {
    Properties overrides = new Properties();
    overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    overrides.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    overrides.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    @SuppressWarnings("unchecked")
    Consumer<String, String> consumer =
        (Consumer<String, String>)
            consumerFactory.createConsumer("dlq-it-" + UUID.randomUUID(), null, null, overrides);
    List<ConsumerRecord<String, String>> seen = new ArrayList<>();
    try (consumer) {
      consumer.subscribe(Set.of(properties.deadLetterTopic()));
      long deadline = System.nanoTime() + WAIT.toNanos();
      while (System.nanoTime() < deadline) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
          seen.add(record);
          if (key.equals(record.key())) {
            return record;
          }
        }
      }
    }
    throw new AssertionError(
        "No dead letter with key " + key + " within " + WAIT + " (saw " + seen.size() + ")");
  }

  private static String header(ConsumerRecord<?, ?> record, String name) {
    return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
  }
}
