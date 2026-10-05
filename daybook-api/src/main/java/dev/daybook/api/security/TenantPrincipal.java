package dev.daybook.api.security;

import java.util.UUID;

/**
 * The authenticated caller, when it is a tenant. Controllers receive it via
 * {@code @AuthenticationPrincipal} and pass its tenant id to every query (FR-1.3).
 */
public record TenantPrincipal(UUID tenantId) {}
