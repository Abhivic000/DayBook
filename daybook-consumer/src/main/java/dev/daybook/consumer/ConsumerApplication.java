package dev.daybook.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Consumes ledger events and maintains the statement projection (ADR 0010, 0011). Runs as its own
 * process with its own database role (ADR 0005), so it can lag, crash or be replayed without
 * affecting the API's write path.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ConsumerApplication {

  public static void main(String[] args) {
    SpringApplication.run(ConsumerApplication.class, args);
  }
}
