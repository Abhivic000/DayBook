package dev.daybook.api.outbox.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Outbox relay settings, bound from {@code daybook.outbox.*}.
 *
 * @param batchSize maximum messages published per relay cycle
 * @param sendTimeout how long one cycle waits for broker acknowledgements
 * @param retention how long published rows are kept before purge
 */
@ConfigurationProperties("daybook.outbox")
public record OutboxProperties(
    @DefaultValue("100") int batchSize,
    @DefaultValue("PT10S") Duration sendTimeout,
    @DefaultValue("P7D") Duration retention) {}
