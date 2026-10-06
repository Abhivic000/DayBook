package dev.daybook.consumer.deadletter;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A record that could not be processed, with where it came from and why it failed.
 *
 * @param originalOffset position in the original topic partition: together with topic and
 *     partition, uniquely identifies the failed record
 * @param exceptionClass the root cause's class, e.g. {@code ...EventGapException}
 */
public record DeadLetter(
    String originalTopic,
    int originalPartition,
    long originalOffset,
    @Nullable String messageKey,
    @Nullable String payload,
    @Nullable String exceptionClass,
    @Nullable String exceptionMessage,
    @Nullable String correlationId,
    Instant failedAt) {}
