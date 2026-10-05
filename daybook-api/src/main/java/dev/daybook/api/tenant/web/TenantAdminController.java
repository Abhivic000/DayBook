package dev.daybook.api.tenant.web;

import dev.daybook.api.tenant.application.TenantService;
import dev.daybook.api.tenant.application.TenantService.ProvisionedTenant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TenantAdminController {

  record CreateTenantRequest(@NotBlank @Size(max = 200) String name) {}

  private final TenantService tenants;

  TenantAdminController(TenantService tenants) {
    this.tenants = tenants;
  }

  /**
   * Creates a tenant and returns its API key — the only time the key is ever shown.
   *
   * <p>Deliberately not idempotent: an idempotent endpoint stores its response for replay, and this
   * response contains a plaintext secret that must never be stored (ADR 0001). A lost response is
   * recovered by creating a new key, not by replaying the old one.
   */
  @PostMapping("/v1/admin/tenants")
  @ResponseStatus(HttpStatus.CREATED)
  ProvisionedTenant create(@Valid @RequestBody CreateTenantRequest body) {
    return tenants.create(body.name());
  }
}
