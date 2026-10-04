package dev.daybook.api.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

  @Test
  void addsAndSubtractsExactly() {
    assertThat(Money.ofMinor(150).plus(Money.ofMinor(50))).isEqualTo(Money.ofMinor(200));
    assertThat(Money.ofMinor(150).minus(Money.ofMinor(200))).isEqualTo(Money.ofMinor(-50));
  }

  @Test
  void overflowThrowsInsteadOfWrappingAround() {
    Money max = Money.ofMinor(Long.MAX_VALUE);

    assertThatThrownBy(() -> max.plus(Money.ofMinor(1))).isInstanceOf(ArithmeticException.class);
    assertThatThrownBy(() -> Money.ofMinor(Long.MIN_VALUE).minus(Money.ofMinor(1)))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void reportsSign() {
    assertThat(Money.ofMinor(1).isPositive()).isTrue();
    assertThat(Money.ZERO.isPositive()).isFalse();
    assertThat(Money.ZERO.isNegative()).isFalse();
    assertThat(Money.ofMinor(-1).isNegative()).isTrue();
  }
}
