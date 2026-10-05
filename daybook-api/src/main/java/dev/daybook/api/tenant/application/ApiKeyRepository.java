package dev.daybook.api.tenant.application;

import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository {

  /** The stored side of an active key: whose it is and the hash to compare against. */
  record ActiveKey(UUID tenantId, String secretHash) {}

  void insert(UUID tenantId, String keyId, String secretHash);

  Optional<ActiveKey> findActive(String keyId);
}
