package dev.daybook.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.web.servlet.MockMvc;

/** Dead letters are recorded once each and visible to an operator over HTTP (FR-9.4). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DeadLetterIT {

  private static final String ADMIN_KEY = "test-admin-key"; // hash in test properties
  private static final Duration WAIT = Duration.ofSeconds(30);

  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired ConsumerProperties properties;
  @Autowired JdbcClient jdbc;
  @Autowired MockMvc mvc;

  @Test
  void failedEventIsRecordedWithItsOriginCauseAndCorrelationId() throws Exception {
    String key = UUID.randomUUID().toString();
    ProducerRecord<String, String> poison = new ProducerRecord<>(properties.topic(), key, "{oops");
    poison.headers().add("X-Correlation-Id", "trace-dlq-1".getBytes(StandardCharsets.UTF_8));
    kafka.send(poison).get();

    await(() -> recorded(key) == 1, "dead letter " + key + " to be recorded");
    Map<String, Object> row =
        jdbc.sql("SELECT * FROM projection.dead_letters WHERE message_key = ?")
            .param(key)
            .query()
            .singleRow();
    assertThat(row.get("original_topic")).isEqualTo(properties.topic());
    assertThat(row.get("payload")).isEqualTo("{oops");
    assertThat(row.get("correlation_id")).isEqualTo("trace-dlq-1");
    assertThat((String) row.get("exception_class")).startsWith("tools.jackson");
    assertThat(row.get("exception_message")).isNotNull();
  }

  @Test
  void theSameDeadLetterArrivingTwiceIsRecordedOnce() throws Exception {
    String key = UUID.randomUUID().toString(); // one key, so all three share a partition, in order
    long originalOffset = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 2);
    kafka.send(deadLetter(key, originalOffset)).get();
    kafka.send(deadLetter(key, originalOffset)).get(); // redelivered duplicate
    kafka.send(deadLetter(key, originalOffset + 1)).get(); // a different failure

    await(() -> recorded(key) == 2, "both distinct dead letters to be recorded");
    assertThat(recorded(key)).isEqualTo(2);
  }

  @Test
  void adminCanReadDlqDepthAndContents() throws Exception {
    String key = UUID.randomUUID().toString();
    kafka.send(new ProducerRecord<>(properties.topic(), key, "not even json")).get();
    await(() -> recorded(key) == 1, "dead letter to be recorded");

    mvc.perform(
            get("/v1/admin/dlq")
                .param("limit", "200")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").isNumber())
        .andExpect(jsonPath("$.deadLetters[?(@.messageKey == '%s')]", key).exists());
  }

  @Test
  void dlqEndpointRequiresTheAdminKeyAndValidatesInput() throws Exception {
    mvc.perform(get("/v1/admin/dlq")).andExpect(status().isUnauthorized());
    mvc.perform(get("/v1/admin/dlq").header(HttpHeaders.AUTHORIZATION, "Bearer wrong-key"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/v1/admin/dlq")
                .param("limit", "0")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_KEY))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
  }

  // --- Helpers ----------------------------------------------------------------------------------

  /** A record shaped like Spring Kafka's dead-letter publisher output. */
  private ProducerRecord<String, String> deadLetter(String key, long originalOffset) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(properties.deadLetterTopic(), key, "payload");
    record
        .headers()
        .add(KafkaHeaders.DLT_ORIGINAL_TOPIC, properties.topic().getBytes(StandardCharsets.UTF_8))
        .add(KafkaHeaders.DLT_ORIGINAL_PARTITION, ByteBuffer.allocate(4).putInt(0).array())
        .add(
            KafkaHeaders.DLT_ORIGINAL_OFFSET,
            ByteBuffer.allocate(8).putLong(originalOffset).array())
        .add(
            KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN,
            "test.SomeFailure".getBytes(StandardCharsets.UTF_8));
    return record;
  }

  private long recorded(String key) {
    return jdbc.sql("SELECT count(*) FROM projection.dead_letters WHERE message_key = ?")
        .param(key)
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
}
