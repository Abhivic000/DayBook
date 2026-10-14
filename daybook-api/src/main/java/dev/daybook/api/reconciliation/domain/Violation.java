package dev.daybook.api.reconciliation.domain;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One broken invariant.
 *
 * @param subjectId the account or transaction concerned, when there is one
 * @param detail human-readable specifics, e.g. expected and actual values
 */
public record Violation(
    Check check, @Nullable UUID tenantId, @Nullable UUID subjectId, String detail) {

  public Violation {
    Objects.requireNonNull(check, "check");
    Objects.requireNonNull(detail, "detail");
  }

  public Check.Severity severity() {
    return check.severity();
  }
}
