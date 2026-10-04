package dev.daybook.api.transaction.domain;

public enum TransactionType {
  /** Between two USER accounts of one tenant; settles synchronously. */
  TRANSFER,
  /** Opening balance from the tenant's TREASURY account to a USER account (admin only). */
  FUNDING
}
