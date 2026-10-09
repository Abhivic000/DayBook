package dev.daybook.pspsim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class PspSimulatorTest {

  @Autowired MockMvc mvc;

  @BeforeEach
  void approveByDefault() throws Exception {
    mode("APPROVE", null);
  }

  @Test
  void approvedPaymentSucceedsAndCanBeLookedUp() throws Exception {
    String ref = ref();

    create(ref, "COLLECT", 500)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    mvc.perform(get("/v1/payments/{ref}", ref))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amountMinor").value(500));
  }

  @Test
  void repeatingAReferenceReturnsTheOriginalPaymentInsteadOfPayingTwice() throws Exception {
    String ref = ref();
    create(ref, "COLLECT", 500).andExpect(status().isCreated());
    mode("DECLINE", null); // a replay must not be re-decided

    create(ref, "COLLECT", 500)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    create(ref, "COLLECT", 999).andExpect(status().isConflict());
  }

  @Test
  void declinedPaymentIsADefinitiveFailure() throws Exception {
    mode("DECLINE", null);

    create(ref(), "PAYOUT", 500)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("DECLINED"));
  }

  @Test
  void downRejectsWithoutRecordingAnything() throws Exception {
    String ref = ref();
    mode("DOWN", null);

    create(ref, "COLLECT", 500).andExpect(status().isServiceUnavailable());
    mvc.perform(get("/v1/payments/{ref}", ref)).andExpect(status().isServiceUnavailable());

    mode("APPROVE", null);
    mvc.perform(get("/v1/payments/{ref}", ref)).andExpect(status().isNotFound());
  }

  @Test
  void slowAnswersAfterTheDelay() throws Exception {
    mode("SLOW", 300L);

    long started = System.nanoTime();
    create(ref(), "COLLECT", 500).andExpect(status().isCreated());

    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
        .isGreaterThanOrEqualTo(300);
  }

  @Test
  void lateRecordsThePaymentBeforeItsAnswerArrives() throws Exception {
    String ref = ref();
    mode("LATE", 1_500L);

    CompletableFuture<ResultActions> lateAnswer =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return create(ref, "COLLECT", 500);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });

    // While the caller is still waiting (and would have timed out), the payment already exists.
    String statusWhileWaiting = awaitStatus(ref);
    assertThat(statusWhileWaiting).isEqualTo("SUCCEEDED");
    assertThat(lateAnswer).isNotDone();

    lateAnswer.get(5, TimeUnit.SECONDS).andExpect(status().isCreated());
  }

  @Test
  void behaviourCanBeReadBack() throws Exception {
    mode("LATE", null);

    mvc.perform(get("/admin/behaviour"))
        .andExpect(jsonPath("$.mode").value("LATE"))
        .andExpect(jsonPath("$.delayMs").value(5_000));
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private ResultActions create(String reference, String direction, long amount) throws Exception {
    return mvc.perform(
        post("/v1/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"reference\":\"%s\",\"direction\":\"%s\",\"amountMinor\":%d}"
                    .formatted(reference, direction, amount)));
  }

  private void mode(String mode, Long delayMs) throws Exception {
    String body =
        delayMs == null
            ? "{\"mode\":\"%s\"}".formatted(mode)
            : "{\"mode\":\"%s\",\"delayMs\":%d}".formatted(mode, delayMs);
    mvc.perform(put("/admin/behaviour").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());
  }

  private String awaitStatus(String ref) throws Exception {
    for (int i = 0; i < 50; i++) {
      var response = mvc.perform(get("/v1/payments/{ref}", ref)).andReturn().getResponse();
      if (response.getStatus() == 200) {
        return com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.status");
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Payment " + ref + " was never recorded");
  }

  private static String ref() {
    return UUID.randomUUID().toString();
  }
}
