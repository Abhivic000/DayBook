package dev.daybook.api.account.domain;

public enum AccountStatus {
  ACTIVE,
  /** No debits or credits are accepted. */
  FROZEN
}
