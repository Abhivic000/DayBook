package dev.daybook.api.transaction.application;

import dev.daybook.api.transaction.domain.Transaction;

public interface TransactionRepository {

  void insert(Transaction transaction);
}
