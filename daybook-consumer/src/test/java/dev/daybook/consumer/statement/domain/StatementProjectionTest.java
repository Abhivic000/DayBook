package dev.daybook.consumer.statement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.daybook.consumer.statement.domain.StatementProjection.Decision;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StatementProjectionTest {

  private static final UUID ACCOUNT = UUID.randomUUID();

  @Test
  void firstEventForAnUnseenAccountIsApplied() {
    assertThat(decide(ProjectedAccount.UNSEEN, event(1, "CREDIT", 500, 500)))
        .isInstanceOf(Decision.Apply.class);
  }

  @Test
  void nextVersionWithConsistentBalanceIsApplied() {
    assertThat(decide(new ProjectedAccount(3, 1_000), event(4, "DEBIT", 300, 700)))
        .isInstanceOf(Decision.Apply.class);
  }

  @Test
  void alreadyAppliedVersionsAreDuplicates() {
    ProjectedAccount current = new ProjectedAccount(5, 1_000);

    assertThat(decide(current, event(5, "CREDIT", 1, 1_000)))
        .isInstanceOf(Decision.Duplicate.class);
    assertThat(decide(current, event(2, "CREDIT", 1, 1))).isInstanceOf(Decision.Duplicate.class);
  }

  @Test
  void skippedVersionIsAGap() {
    Decision decision = decide(new ProjectedAccount(3, 1_000), event(5, "CREDIT", 10, 1_010));

    assertThat(decision).isEqualTo(new Decision.Gap(4));
  }

  @Test
  void balanceThatDoesNotFollowIsAMismatch() {
    Decision decision = decide(new ProjectedAccount(1, 1_000), event(2, "DEBIT", 100, 950));

    assertThat(decision).isEqualTo(new Decision.Mismatch(900));
  }

  @Test
  void systemAccountsMayGoNegative() {
    assertThat(decide(new ProjectedAccount(1, -500), event(2, "DEBIT", 200, -700)))
        .isInstanceOf(Decision.Apply.class);
  }

  @Test
  void invalidEventsCannotBeConstructed() {
    assertThatThrownBy(() -> event(0, "CREDIT", 1, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> event(1, "SIDEWAYS", 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> event(1, "CREDIT", 0, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  private static Decision decide(ProjectedAccount current, AccountEntryEvent event) {
    return StatementProjection.decide(current, event);
  }

  private static AccountEntryEvent event(
      long version, String direction, long amount, long balanceAfter) {
    return new AccountEntryEvent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        ACCOUNT,
        "USER",
        version,
        UUID.randomUUID(),
        "TRANSFER",
        direction,
        amount,
        balanceAfter,
        Instant.parse("2026-10-06T10:00:00Z"));
  }
}
