package dev.daybook.api.common.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Carries a trace across an asynchronous hand-off that is not a network call — the outbox
 * (FR-11.4). The writer saves the current trace as a W3C {@code traceparent} string; whoever picks
 * the work up later continues that trace instead of starting an unrelated one.
 *
 * <p>When tracing is off (tests, or no tracer configured) this is a no-op: nothing is captured and
 * work runs exactly as before.
 */
@Component
public class TraceParents {

  private static final String TRACEPARENT = "traceparent";

  private final Tracer tracer;
  private final Propagator propagator;

  TraceParents(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
    this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
    this.propagator = propagator.getIfAvailable(() -> Propagator.NOOP);
  }

  /** The current span as a W3C traceparent, or null if nothing is being traced. */
  public @Nullable String current() {
    Span span = tracer.currentSpan();
    if (span == null) {
      return null;
    }
    Map<String, String> carrier = new HashMap<>();
    propagator.inject(span.context(), carrier, Map::put);
    return carrier.get(TRACEPARENT);
  }

  /**
   * Runs {@code action} inside a new span named {@code name} that continues {@code traceparent} (or
   * starts a fresh trace if it is null). The span ends when the returned future completes, so it
   * measures the whole asynchronous operation, e.g. until the broker acknowledges a message.
   */
  // The whenComplete future is deliberately dropped: callers keep the original future, so its
  // exceptions reach them unwrapped.
  @SuppressWarnings("FutureReturnValueIgnored")
  public <T> CompletableFuture<T> continueTrace(
      @Nullable String traceparent, String name, Supplier<CompletableFuture<T>> action) {
    Span span =
        traceparent == null
            ? tracer.nextSpan().name(name).start()
            : propagator.extract(Map.of(TRACEPARENT, traceparent), Map::get).name(name).start();
    CompletableFuture<T> future;
    try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
      future = action.get();
    } catch (RuntimeException e) {
      span.error(e).end();
      throw e;
    }
    future.whenComplete(
        (result, error) -> {
          if (error != null) {
            span.error(error);
          }
          span.end();
        });
    return future;
  }
}
