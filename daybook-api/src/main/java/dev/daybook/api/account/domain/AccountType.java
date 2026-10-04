package dev.daybook.api.account.domain;

public enum AccountType {
  /** A tenant's end-user wallet. Never allowed to go negative. */
  USER,
  /** Per-tenant system account that funds opening balances (ADR 0002 amendment). */
  TREASURY
}
