package dev.daybook.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.daybook.api.support.ApiClient;
import dev.daybook.api.support.ApiClient.Tenant;
import dev.daybook.api.support.ApiIntegrationTest;
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
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * One trace from the HTTP request, through the outbox, onto the Kafka record (FR-11.4). Tracing is
 * off in other tests, so this class runs in its own Spring context.
 */
@ApiIntegrationTest
@AutoConfigureTracing
@TestPropertySource(properties = "management.tracing.sampling.probability=1.0")
class OutboxTracingIT {

  private static final String TOPIC = "daybook.account-entries.v1";
  private static final Duration WAIT = Duration.ofSeconds(30);

  @Autowired ApiClient api;
  @Autowired JdbcClient jdbc;
  @Autowired JsonMapper json;
  @Autowired ConsumerFactory<?, ?> consumerFactory;

  @Test
  void theRequestsTraceIsStoredInTheOutboxAndContinuedOnTheKafkaRecord() throws Exception {
    Tenant tenant = api.createTenant();
    UUID from = api.createFundedAccount(tenant, 10_000);
    UUID to = api.createAccount(tenant);

    String body =
        api.transfer(tenant, "trace-1", from, to, 1_000)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String transactionId = json.readTree(body).get("id").asString();

    // Both entries were written by one request, so both rows carry that request's trace.
    List<String> stored =
        jdbc.sql(
                """
                SELECT traceparent FROM outbox_events
                 WHERE payload::jsonb ->> 'transactionId' = ?
                """)
            .param(transactionId)
            .query(String.class)
            .list();
    assertThat(stored).hasSize(2).allMatch(t -> t != null && t.matches(W3C));
    String traceId = traceId(stored.get(0));
    assertThat(stored).allMatch(t -> traceId(t).equals(traceId));

    // The published records continue that same trace, in a span of their own (the publish).
    List<ConsumerRecord<String, String>> records = consume(transactionId, 2);
    for (ConsumerRecord<String, String> record : records) {
      Header header = record.headers().lastHeader("traceparent");
      assertThat(header).as("traceparent header").isNotNull();
      String sent = new String(header.value(), StandardCharsets.UTF_8);
      assertThat(sent).matches(W3C);
      assertThat(traceId(sent)).isEqualTo(traceId);
      assertThat(sent).isNotIn(stored); // a child span, not the request's span itself
    }
  }

  /** version-traceId-spanId-flags, e.g. 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-03. */
  private static final String W3C = "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}";

  private static String traceId(String traceparent) {
    return traceparent.substring(3, 35); // the 32 hex characters after "00-"
  }

  private List<ConsumerRecord<String, String>> consume(String transactionId, int expected) {
    Properties overrides = new Properties();
    overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    overrides.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    overrides.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    @SuppressWarnings("unchecked")
    Consumer<String, String> consumer =
        (Consumer<String, String>)
            consumerFactory.createConsumer(
                "tracing-it-" + UUID.randomUUID(), null, null, overrides);
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    try (consumer) {
      consumer.subscribe(Set.of(TOPIC));
      long deadline = System.nanoTime() + WAIT.toNanos();
      while (System.nanoTime() < deadline) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
          if (record.value().contains(transactionId)) {
            found.add(record);
          }
        }
        if (found.size() >= expected) {
          return found;
        }
      }
    }
    throw new AssertionError(
        "Expected records not consumed within " + WAIT + "; found " + found.size());
  }
}
