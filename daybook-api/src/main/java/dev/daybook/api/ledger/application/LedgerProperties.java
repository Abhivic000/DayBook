package dev.daybook.api.ledger.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Ledger-wide limits, bound from {@code daybook.ledger.*}.
 *
 * @param maxTransactionAmountMinor upper bound on any single transaction, in minor units. Default
 *     ₹1,00,00,000 (one crore). Keeps balances far from {@code long} overflow and limits blast
 *     radius of a bad request.
 */
@ConfigurationProperties("daybook.ledger")
public record LedgerProperties(@DefaultValue("1000000000") long maxTransactionAmountMinor) {}
