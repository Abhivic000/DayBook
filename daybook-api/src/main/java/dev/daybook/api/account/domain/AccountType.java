package dev.daybook.api.account.domain;

public enum AccountType {
  /** A tenant's end-user wallet. Never allowed to go negative. */
  USER(false),
  /** Per-tenant system account that funds opening balances (ADR 0002 amendment). */
  TREASURY(true),
  /** Per-tenant counterparty for money entering or leaving through the PSP (ADR 0002). */
  PSP_SETTLEMENT(true),
  /**
   * Per-tenant account holding funds of withdrawals awaiting the PSP (ADR 0006). Its balance is the
   * sum of open holds, so it can never legitimately be negative.
   */
  WITHDRAWAL_IN_TRANSIT(false);

  private final boolean mayGoNegative;

  AccountType(boolean mayGoNegative) {
    this.mayGoNegative = mayGoNegative;
  }

  public boolean mayGoNegative() {
    return mayGoNegative;
  }

  /** Accounts the system creates for every tenant; tenants can never create these. */
  public boolean isSystem() {
    return this != USER;
  }
}
