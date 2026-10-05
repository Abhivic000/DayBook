package dev.daybook.api.tenant.application;

import dev.daybook.api.account.application.AccountRepository;
import dev.daybook.api.account.domain.Account;
import dev.daybook.api.account.domain.AccountType;
import dev.daybook.api.tenant.domain.ApiKeys;
import dev.daybook.api.tenant.domain.TenantNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant provisioning (admin only). */
@Service
public class TenantService {

  /** A newly created tenant. {@code apiKey} is the only time the plaintext key is available. */
  public record ProvisionedTenant(UUID tenantId, String name, String apiKey) {
    @Override
    public String toString() {
      return "ProvisionedTenant[tenantId=" + tenantId + ", name=" + name + ", apiKey=<redacted>]";
    }
  }

  private final TenantRepository tenants;
  private final ApiKeyRepository apiKeys;
  private final AccountRepository accounts;

  TenantService(TenantRepository tenants, ApiKeyRepository apiKeys, AccountRepository accounts) {
    this.tenants = tenants;
    this.apiKeys = apiKeys;
    this.accounts = accounts;
  }

  /** Creates the tenant, its system accounts (ADR 0002) and its first API key, atomically. */
  @Transactional
  public ProvisionedTenant create(String name) {
    UUID tenantId = UUID.randomUUID();
    tenants.insert(tenantId, name);
    accounts.insert(Account.open(UUID.randomUUID(), tenantId, AccountType.TREASURY));
    ApiKeys.Generated key = ApiKeys.generate();
    apiKeys.insert(tenantId, key.keyId(), key.secretHash());
    return new ProvisionedTenant(tenantId, name, key.plaintext());
  }

  public void requireExists(UUID tenantId) {
    if (!tenants.exists(tenantId)) {
      throw new TenantNotFoundException(tenantId);
    }
  }
}
