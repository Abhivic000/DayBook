package dev.daybook.api.reconciliation.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Reconciliation settings, bound from {@code daybook.reconciliation.*}.
 *
 * @param stuckPendingAfter a transaction PENDING longer than this is a STUCK_PENDING warning
 * @param outboxLagAfter an outbox event unpublished longer than this is an OUTBOX_LAG warning
 * @param projectionStaleAfter an account whose projection is still behind this long after its last
 *     ledger entry is a PROJECTION_STALE warning
 * @param statementTimeout per-query limit, so a runaway check cannot hurt the database
 * @param maxViolationsPerCheck cap on rows reported per check, so one systemic fault cannot produce
 *     an unbounded report
 * @param retention how long run reports are kept
 */
@ConfigurationProperties("daybook.reconciliation")
public record ReconciliationProperties(
    @DefaultValue("PT30M") Duration stuckPendingAfter,
    @DefaultValue("PT5M") Duration outboxLagAfter,
    @DefaultValue("PT5M") Duration projectionStaleAfter,
    @DefaultValue("PT60S") Duration statementTimeout,
    @DefaultValue("1000") int maxViolationsPerCheck,
    @DefaultValue("P90D") Duration retention) {}
