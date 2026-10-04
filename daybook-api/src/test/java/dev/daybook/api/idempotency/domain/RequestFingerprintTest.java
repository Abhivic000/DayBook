package dev.daybook.api.idempotency.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

  @Test
  void isDeterministicSha256Hex() {
    String a = RequestFingerprint.of("POST", "/v1/transfers", "{\"amount\":100}");
    String b = RequestFingerprint.of("POST", "/v1/transfers", "{\"amount\":100}");

    assertThat(a).isEqualTo(b).hasSize(64).matches("[0-9a-f]+");
  }

  @Test
  void differsWhenMethodPathOrBodyDiffers() {
    String base = RequestFingerprint.of("POST", "/v1/transfers", "{\"amount\":100}");

    assertThat(RequestFingerprint.of("POST", "/v1/transfers", "{\"amount\":101}"))
        .isNotEqualTo(base);
    assertThat(RequestFingerprint.of("POST", "/v1/accounts", "{\"amount\":100}"))
        .isNotEqualTo(base);
    assertThat(RequestFingerprint.of("PUT", "/v1/transfers", "{\"amount\":100}"))
        .isNotEqualTo(base);
  }
}
