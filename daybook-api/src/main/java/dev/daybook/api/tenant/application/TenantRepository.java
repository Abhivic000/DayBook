package dev.daybook.api.tenant.application;

import java.util.UUID;

public interface TenantRepository {

  void insert(UUID id, String name);

  boolean exists(UUID id);
}
