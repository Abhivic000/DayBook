package dev.daybook.api.transaction.domain;

public enum TransactionType {
  /** Between two USER accounts of one tenant; settles synchronously. */
  TRANSFER,
  /** Opening balance from the tenant's TREASURY account to a USER account (admin only). */
  FUNDING,
  /** Money in from the PSP to a USER account; two-phase. */
  TOPUP,
  /** Money out from a USER account to the PSP; two-phase, with a hold (ADR 0006). */
  WITHDRAWAL,
  /** Compensating transaction undoing another (ADR 0003). */
  REVERSAL
}
