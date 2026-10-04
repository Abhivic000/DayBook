package dev.daybook.api.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.daybook.api.common.domain.Money;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingTest {

  private final UUID a = UUID.randomUUID();
  private final UUID b = UUID.randomUUID();
  private final UUID c = UUID.randomUUID();

  @Test
  void moveProducesOneDebitAndOneCredit() {
    Posting posting = Posting.move(a, b, Money.ofMinor(500));

    assertThat(posting.entries())
        .containsExactly(Entry.debit(a, Money.ofMinor(500)), Entry.credit(b, Money.ofMinor(500)));
    assertThat(posting.accountIds()).containsExactlyInAnyOrder(a, b);
  }

  @Test
  void acceptsBalancedMultiEntryPostings() {
    Posting posting =
        new Posting(
            List.of(
                Entry.debit(a, Money.ofMinor(100)),
                Entry.credit(b, Money.ofMinor(60)),
                Entry.credit(c, Money.ofMinor(40))));

    assertThat(posting.entries()).hasSize(3);
  }

  @Test
  void rejectsUnbalancedPostings() {
    assertThatThrownBy(
            () ->
                new Posting(
                    List.of(
                        Entry.debit(a, Money.ofMinor(100)), Entry.credit(b, Money.ofMinor(99)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unbalanced");
  }

  @Test
  void rejectsPostingsWithoutBothSides() {
    assertThatThrownBy(() -> new Posting(List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Posting(List.of(Entry.debit(a, Money.ofMinor(1)))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMovingMoneyToTheSameAccount() {
    assertThatThrownBy(() -> Posting.move(a, a, Money.ofMinor(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void entriesMustBePositive() {
    assertThatThrownBy(() -> Entry.debit(a, Money.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
