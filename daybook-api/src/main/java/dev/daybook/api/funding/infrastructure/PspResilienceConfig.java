package dev.daybook.api.funding.infrastructure;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** The PSP's HTTP client and its resilience components (FR-7). */
@Configuration(proxyBeanMethods = false)
class PspResilienceConfig {

  private static final Logger log = LoggerFactory.getLogger(PspResilienceConfig.class);

  @Bean
  RestClient pspRestClient(PspProperties properties) {
    // HTTP/1.1 explicitly: the JDK client otherwise attempts an HTTP/2 upgrade over plain http,
    // which some servers accept and then cancel ("RST_STREAM") — every call would then look like
    // an unknown outcome. A request/response payments API gains nothing from HTTP/2.
    HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.connectTimeout())
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());
    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
        .build();
  }

  /**
   * Opens when too many recent PSP calls failed; while open, callers fail fast instead of each
   * waiting for a timeout. Business outcomes (declined) are successes here — only the PSP being
   * unavailable or unresponsive counts as failure.
   */
  @Bean
  CircuitBreaker pspCircuitBreaker(PspProperties properties, MeterRegistry meters) {
    PspProperties.CircuitBreaker settings = properties.circuitBreaker();
    CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
    // FR-7.5, FR-11.3: state, call outcomes and failure rate, e.g.
    // resilience4j_circuitbreaker_state.
    TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
    CircuitBreaker breaker =
        registry.circuitBreaker(
            "psp",
            CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumNumberOfCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.waitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(settings.permittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(
                    HttpPspGateway.PspNotAcceptedException.class,
                    HttpPspGateway.PspUnknownOutcomeException.class)
                .build());
    // FR-7.5: state changes are also logged, so they show up next to the requests they affect.
    breaker
        .getEventPublisher()
        .onStateTransition(event -> log.warn("PSP circuit breaker {}", event.getStateTransition()));
    return breaker;
  }

  /**
   * Retries only failures where the PSP provably did not accept the request. Exponential backoff
   * with random jitter, so many clients retrying at once do not hit the PSP in lockstep.
   */
  @Bean
  Retry pspRetry(PspProperties properties, MeterRegistry meters) {
    RetryRegistry registry = RetryRegistry.ofDefaults();
    TaggedRetryMetrics.ofRetryRegistry(registry).bindTo(meters);
    return registry.retry(
        "psp",
        RetryConfig.custom()
            .maxAttempts(properties.retry().maxAttempts())
            .intervalFunction(
                IntervalFunction.ofExponentialRandomBackoff(
                    properties.retry().initialBackoff(), 2.0, 0.5))
            .retryOnException(
                e ->
                    e instanceof HttpPspGateway.PspNotAcceptedException notAccepted
                        && notAccepted.retryable())
            .build());
  }

  /** At most N PSP calls in flight; further calls are refused at once, not queued. */
  @Bean
  Bulkhead pspBulkhead(PspProperties properties, MeterRegistry meters) {
    BulkheadRegistry registry = BulkheadRegistry.ofDefaults();
    TaggedBulkheadMetrics.ofBulkheadRegistry(registry).bindTo(meters);
    return registry.bulkhead(
        "psp",
        BulkheadConfig.custom()
            .maxConcurrentCalls(properties.bulkhead().maxConcurrentCalls())
            .maxWaitDuration(Duration.ZERO)
            .build());
  }
}
