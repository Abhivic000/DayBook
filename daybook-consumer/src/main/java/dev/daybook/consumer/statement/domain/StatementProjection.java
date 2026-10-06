package dev.daybook.consumer.statement.domain;

/**
 * The projection's rules for one incoming event, as pure logic.
 *
 * <p>Delivery is at-least-once and a partition is read in order, so for one account an event is
 * either the next one expected, one already applied (a duplicate), or proof that something is
 * missing (a gap). Only the next expected event is ever applied.
 */
public final class StatementProjection {

  private StatementProjection() {}

  /** The outcome of {@link #decide}. */
  public sealed interface Decision {

    /** The next expected event: apply it. */
    record Apply() implements Decision {}

    /** Already applied (at-least-once redelivery): skip it, harmlessly. */
    record Duplicate() implements Decision {}

    /** An earlier event is missing: applying this one would corrupt the running balance. */
    record Gap(long expectedVersion) implements Decision {}

    /**
     * The event's version fits, but its balance does not follow from the previous one — the
     * producer and the projection disagree about history.
     */
    record Mismatch(long expectedBalanceMinor) implements Decision {}
  }

  public static Decision decide(ProjectedAccount current, AccountEntryEvent event) {
    if (event.accountVersion() <= current.lastVersion()) {
      return new Decision.Duplicate();
    }
    long expectedVersion = current.lastVersion() + 1;
    if (event.accountVersion() != expectedVersion) {
      return new Decision.Gap(expectedVersion);
    }
    long expectedBalance = Math.addExact(current.balanceMinor(), event.signedAmountMinor());
    if (event.balanceAfterMinor() != expectedBalance) {
      return new Decision.Mismatch(expectedBalance);
    }
    return new Decision.Apply();
  }
}
