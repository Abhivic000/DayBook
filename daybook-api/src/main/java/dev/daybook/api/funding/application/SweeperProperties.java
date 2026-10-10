package dev.daybook.api.funding.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the pending-transaction sweeper (FR-6.5), bound from {@code daybook.sweeper.*}.
 *
 * @param minAge only transactions at least this old are swept, so a request still waiting on its
 *     own PSP call is never raced
 * @param maxPendingAge beyond this, a still-PENDING transaction is flagged as stale for
 *     reconciliation and an operator — never auto-resolved
 * @param batchSize maximum transactions examined per run, oldest first
 */
@ConfigurationProperties("daybook.sweeper")
public record SweeperProperties(
    @DefaultValue("PT60S") Duration minAge,
    @DefaultValue("PT24H") Duration maxPendingAge,
    @DefaultValue("100") int batchSize) {}
