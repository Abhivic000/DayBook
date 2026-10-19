package dev.daybook.api.outbox.application;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

@Configuration(proxyBeanMethods = false)
class OutboxObservationConfig {

  /**
   * Spring traces every {@code @Scheduled} run. The relay polls five times a second, so that would
   * be five empty traces a second burying the real ones. Its publishing is traced anyway, as part
   * of the request that wrote each message ({@code TraceParents}).
   */
  @Bean
  ObservationPredicate ignoreOutboxPolling() {
    return (name, context) ->
        !(context instanceof ScheduledTaskObservationContext task
            && task.getTargetClass() == OutboxJobs.class);
  }
}
