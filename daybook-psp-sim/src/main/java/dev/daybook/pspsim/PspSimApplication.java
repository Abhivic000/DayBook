package dev.daybook.pspsim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Stands in for an external payment service provider (ADR 0015). Test infrastructure, not part of
 * Daybook: its job is to make the outside world's failure modes reproducible on demand.
 */
@SpringBootApplication
public class PspSimApplication {

  public static void main(String[] args) {
    SpringApplication.run(PspSimApplication.class, args);
  }
}
