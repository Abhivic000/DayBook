package dev.daybook.api.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} jobs (idempotency purge now; sweepers and reconciliation later). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {}
