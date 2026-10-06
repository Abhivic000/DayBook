package dev.daybook.api.outbox.application;

import dev.daybook.api.outbox.application.OutboxRepository.PendingMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes outbox messages to the broker in insertion order (FR-8.2) and marks each published only
 * after the broker acknowledges it (FR-8.3).
 *
 * <p>Delivery is at-least-once (FR-8.4): a crash after the broker's ack but before {@code
 * published_at} commits republishes the message. Consumers deduplicate.
 */
@Service
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxRepository outbox;
  private final MessagePublisher publisher;
  private final OutboxProperties properties;

  OutboxRelay(OutboxRepository outbox, MessagePublisher publisher, OutboxProperties properties) {
    this.outbox = outbox;
    this.publisher = publisher;
    this.properties = properties;
  }

  /**
   * Publishes one batch. Returns the number of messages acknowledged, or 0 if another instance
   * holds the relay lock.
   *
   * <p>Runs in one database transaction so the advisory lock (ADR 0004) is held for the whole
   * cycle. On the first failure it stops: later messages stay unpublished and are retried next
   * cycle, so a broker outage leaves the outbox accumulating rather than losing anything.
   */
  @Transactional
  public int relayOnce() {
    if (!outbox.tryLockRelay()) {
      return 0;
    }
    List<PendingMessage> batch = outbox.fetchUnpublished(properties.batchSize());
    if (batch.isEmpty()) {
      return 0;
    }

    // Hand every message to the client in order without waiting: the producer keeps per-partition
    // order, and sending one-by-one would cost a broker round trip per message.
    List<PendingMessage> sent = new ArrayList<>();
    List<CompletableFuture<?>> acks = new ArrayList<>();
    for (PendingMessage pending : batch) {
      try {
        acks.add(publisher.publish(pending.message()));
        sent.add(pending);
      } catch (RuntimeException e) {
        fail(pending, e);
        break;
      }
    }

    // Wait for acks in order; mark published only up to the first failure.
    List<Long> published = new ArrayList<>();
    long deadline = System.nanoTime() + properties.sendTimeout().toNanos();
    for (int i = 0; i < sent.size(); i++) {
      try {
        acks.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        published.add(sent.get(i).id());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (ExecutionException | TimeoutException e) {
        fail(sent.get(i), e);
        break;
      }
    }
    outbox.markPublished(published);
    return published.size();
  }

  private void fail(PendingMessage pending, Exception e) {
    Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
    String reason = cause.getClass().getSimpleName() + ": " + cause.getMessage();
    log.warn("Outbox publish failed for message {}; will retry: {}", pending.id(), reason);
    outbox.markFailed(pending.id(), reason);
  }
}
