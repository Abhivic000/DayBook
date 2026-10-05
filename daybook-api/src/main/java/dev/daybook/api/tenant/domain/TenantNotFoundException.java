package dev.daybook.api.tenant.domain;

import dev.daybook.api.common.domain.NotFoundException;
import java.util.UUID;

public class TenantNotFoundException extends NotFoundException {

  public TenantNotFoundException(UUID tenantId) {
    super("Tenant %s not found".formatted(tenantId));
  }
}
