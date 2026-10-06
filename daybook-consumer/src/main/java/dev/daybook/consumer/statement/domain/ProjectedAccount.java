package dev.daybook.consumer.statement.domain;

/** What the projection currently knows about one account. */
public record ProjectedAccount(long lastVersion, long balanceMinor) {

  /** An account the projection has not seen yet: ledger accounts start at version 0, balance 0. */
  public static final ProjectedAccount UNSEEN = new ProjectedAccount(0, 0);
}
