package dev.daybook.api.funding.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * PSP connection and resilience settings, bound from {@code daybook.psp.*} (FR-7).
 *
 * @param baseUrl where the PSP (or simulator) listens
 * @param connectTimeout how long to wait to establish a connection; failing here means the request
 *     never reached the PSP, so it is safe to retry
 * @param readTimeout how long to wait for an answer once the request is sent; failing here means
 *     the outcome is unknown and must not be retried blindly
 */
@ConfigurationProperties("daybook.psp")
public record PspProperties(
    @DefaultValue("http://localhost:8090") String baseUrl,
    @DefaultValue("PT1S") Duration connectTimeout,
    @DefaultValue("PT2S") Duration readTimeout,
    @DefaultValue Retry retry,
    @DefaultValue CircuitBreaker circuitBreaker,
    @DefaultValue Bulkhead bulkhead) {

  /**
   * Retries for requests the PSP provably did not accept.
   *
   * @param maxAttempts total attempts including the first
   * @param initialBackoff wait before the first retry; doubles each time, with random jitter
   */
  public record Retry(
      @DefaultValue("3") int maxAttempts, @DefaultValue("PT0.2S") Duration initialBackoff) {}

  /**
   * When to stop calling a failing PSP.
   *
   * @param failureRateThreshold percentage of failed calls in the window that opens the breaker
   * @param slidingWindowSize number of most recent calls considered
   * @param minimumNumberOfCalls calls needed before the failure rate is evaluated at all
   * @param waitInOpenState how long the breaker stays open before letting test calls through
   * @param permittedCallsInHalfOpenState test calls allowed while half-open
   */
  public record CircuitBreaker(
      @DefaultValue("50") float failureRateThreshold,
      @DefaultValue("20") int slidingWindowSize,
      @DefaultValue("10") int minimumNumberOfCalls,
      @DefaultValue("PT30S") Duration waitInOpenState,
      @DefaultValue("3") int permittedCallsInHalfOpenState) {}

  /**
   * Limit on PSP calls in flight at once, so a slow PSP cannot occupy every request thread.
   *
   * @param maxConcurrentCalls beyond this, a call is refused immediately rather than queued
   */
  public record Bulkhead(@DefaultValue("20") int maxConcurrentCalls) {}
}
