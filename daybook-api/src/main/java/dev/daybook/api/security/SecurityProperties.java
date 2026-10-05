package dev.daybook.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Security settings, bound from {@code daybook.security.*}.
 *
 * @param adminKeySha256 hex SHA-256 of the admin API key (ADR 0001). Blank disables admin access
 *     entirely — fail closed, never open.
 */
@ConfigurationProperties("daybook.security")
public record SecurityProperties(@DefaultValue("") String adminKeySha256) {}
