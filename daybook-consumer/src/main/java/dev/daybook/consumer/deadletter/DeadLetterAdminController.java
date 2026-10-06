package dev.daybook.consumer.deadletter;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Lets an operator see what failed and why (FR-9.4, ADR 0012). Admin key required. */
@RestController
class DeadLetterAdminController {

  static final int MAX_LIMIT = 200;

  /**
   * DLQ contents, newest first.
   *
   * @param total DLQ depth: every dead letter recorded, not just this page
   */
  record DeadLetterPage(long total, List<DeadLetter> deadLetters) {}

  private final DeadLetterRepository deadLetters;

  DeadLetterAdminController(DeadLetterRepository deadLetters) {
    this.deadLetters = deadLetters;
  }

  @GetMapping("/v1/admin/dlq")
  DeadLetterPage list(@RequestParam(defaultValue = "50") int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
    }
    return new DeadLetterPage(deadLetters.count(), deadLetters.newest(limit));
  }
}
