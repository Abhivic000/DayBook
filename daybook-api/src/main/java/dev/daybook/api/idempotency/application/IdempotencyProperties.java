package dev.daybook.api.idempotency.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Idempotency settings, bound from {@code daybook.idempotency.*}.
 *
 * @param retention how long a key is remembered; a retry after this may be applied again
 * @param purgeInterval how often expired keys are deleted
 */
@ConfigurationProperties("daybook.idempotency")
public record IdempotencyProperties(
    @DefaultValue("PT24H") Duration retention, @DefaultValue("PT1H") Duration purgeInterval) {}
